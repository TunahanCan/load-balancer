package com.can.loadbalancer;

import com.can.loadbalancer.config.LoadBalancerConfig;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Küme düğümlerinin multicast üzerinden paylaştığı "HELLO" mesajlarını dinleyip
 * yük dengeleyicinin kullanacağı istemci uç noktası görünümünü günceller.
 */
@Startup
@Singleton
public class  ClusterAnnouncementListener implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ClusterAnnouncementListener.class);
    private static final int MAX_PACKET_SIZE = 1024;

    private final ClusterMembershipView membershipView;
    private final LoadBalancerConfig config;
    private final Vertx vertx;
    private final boolean enabled;
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    private MulticastSocket socket;
    private InetAddress groupAddress;
    private NetworkInterface networkInterface;
    private Thread listenerThread;
    private volatile boolean running;
    private long reapTimerId = -1L;

    @Inject
    public ClusterAnnouncementListener(ClusterMembershipView membershipView,
                                       LoadBalancerConfig config,
                                       Vertx vertx) {
        this.membershipView = Objects.requireNonNull(membershipView, "membershipView");
        this.config = Objects.requireNonNull(config, "config");
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.enabled = config.loadBalancer().enabled();
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            LOG.info("Yük dengeleyici devre dışı olduğu için üyelik dinleyicisi başlatılmadı");
            return;
        }

        // Önce statik node'ları ekle
        registerStaticNodes();

        try {
            int port = config.cluster().discovery().multicastPort();
            socket = new MulticastSocket(port);
            socket.setReuseAddress(true);
            groupAddress = InetAddress.getByName(config.cluster().discovery().multicastGroup());
            networkInterface = selectInterface();
            tryJoinMulticastGroup(port);
        } catch (IOException e) {
            LOG.warnf("Multicast duyurularını dinlemek için soket oluşturulamadı: %s. " +
                    "Sadece statik node'lar kullanılacak.", e.getMessage());
            closeSocketQuietly();
            logConnectedNodes();
            return;
        }

        running = true;
        listenerThread = Thread.ofVirtual()
                .name("lb-membership-listener")
                .start(this::listenLoop);

        long heartbeat = Math.max(1000L, config.cluster().discovery().heartbeatIntervalMillis());
        long reapInterval = Math.max(heartbeat, config.cluster().discovery().failureTimeoutMillis() / 2);
        reapTimerId = vertx.setPeriodic(reapInterval, _ -> pruneExpiredMembers());

        // Her 10 saniyede bir bağlı node'ları logla
        vertx.setPeriodic(10_000L, _ -> logConnectedNodes());

        LOG.infof("Küme duyuruları %s:%d adresinden dinleniyor", groupAddress.getHostAddress(),
                config.cluster().discovery().multicastPort());
    }

    private void registerStaticNodes() {
        config.loadBalancer().staticNodes().ifPresent(nodes -> {
            LOG.infof("Statik node listesi yükleniyor: %d node", nodes.size());
            for (String node : nodes) {
                try {
                    String[] parts = node.split(":");
                    if (parts.length == 2) {
                        String host = parts[0].trim();
                        int port = Integer.parseInt(parts[1].trim());
                        String nodeId = "static-" + host + "-" + port;
                        membershipView.upsert(nodeId, host, port);
                        lastSeen.put(nodeId, Long.MAX_VALUE); // Statik node'lar asla expire olmaz
                        LOG.infof(">>> Statik node eklendi: %s (%s:%d)", nodeId, host, port);
                    } else {
                        LOG.warnf("Geçersiz statik node formatı: %s (beklenen: host:port)", node);
                    }
                } catch (NumberFormatException e) {
                    LOG.warnf("Geçersiz port değeri statik node'da: %s", node);
                }
            }
        });
    }

    private void logConnectedNodes() {
        var endpoints = membershipView.snapshot();
        long now = System.currentTimeMillis();

        if (endpoints.isEmpty()) {
            LOG.info("=== Bağlı backend node yok ===");
            return;
        }

        LOG.infof("=== Bağlı Backend Node'lar (%d adet) ===", endpoints.size());
        for (var ep : endpoints) {
            Long lastSeenTime = lastSeen.get(ep.nodeId());
            long ageMs = lastSeenTime != null ? now - lastSeenTime : -1;
            String status = ageMs >= 0 && ageMs < 15000 ? "HEALTHY" : "STALE";
            LOG.infof("  [%s] %s:%d - %s (son görülme: %dms önce)",
                    status, ep.host(), ep.port(), ep.nodeId(), ageMs);
        }
        LOG.info("=====================================");
    }

    private void tryJoinMulticastGroup(int port) throws IOException {
        InetSocketAddress groupSocketAddress = new InetSocketAddress(groupAddress, port);

        // First try with the selected interface
        if (networkInterface != null) {
            try {
                socket.joinGroup(groupSocketAddress, networkInterface);
                return;
            } catch (IOException e) {
                LOG.debugf("Seçilen arayüz ile multicast gruba katılınamadı (%s): %s",
                        networkInterface.getName(), e.getMessage());
            }
        }

        // Try loopback interface as fallback (useful for local development)
        try {
            NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
            if (loopback != null && loopback.supportsMulticast()) {
                socket.joinGroup(groupSocketAddress, loopback);
                networkInterface = loopback;
                LOG.info("Multicast gruba loopback arayüzü ile katılındı");
                return;
            }
        } catch (IOException e) {
            LOG.debugf("Loopback ile multicast gruba katılınamadı: %s", e.getMessage());
        }

        // Try all available interfaces
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            try {
                if (ni.isUp() && ni.supportsMulticast()) {
                    socket.joinGroup(groupSocketAddress, ni);
                    networkInterface = ni;
                    LOG.infof("Multicast gruba %s arayüzü ile katılındı", ni.getName());
                    return;
                }
            } catch (IOException e) {
                LOG.debugf("Arayüz %s ile multicast gruba katılınamadı: %s", ni.getName(), e.getMessage());
            }
        }

        throw new IOException("Hiçbir ağ arayüzü ile multicast gruba katılınamadı");
    }

    private void closeSocketQuietly() {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            socket = null;
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running) {
            try {
                socket.receive(packet);
                handlePacket(packet);
            } catch (IOException e) {
                if (running) {
                    LOG.debug("Multicast paketini alırken hata oluştu", e);
                }
            } catch (Exception e) {
                LOG.warn("Paket işlenirken beklenmeyen hata", e);
            }
            packet.setLength(buffer.length);
        }
    }

    private void handlePacket(DatagramPacket packet) {
        String message = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
        LOG.debugf("Multicast paketi alındı: %s (from %s)", message, packet.getAddress().getHostAddress());

        String[] parts = message.split("\\|");
        if (parts.length < 6 || !Objects.equals(parts[0], config.network().agreementPackMessage())) {
            LOG.debugf("Paket reddedildi - parts: %d, expected prefix: %s, got: %s",
                    parts.length, config.network().agreementPackMessage(), parts.length > 0 ? parts[0] : "empty");
            return;
        }

        String nodeId = parts[1];
        String host = normaliseHost(parts[2], packet.getAddress());
        int clientPort;
        try {
            clientPort = Integer.parseInt(parts[5]);
        } catch (NumberFormatException _) {
            LOG.debugf("Geçersiz port değeri: %s", parts[5]);
            clientPort = 0;
        }

        if (clientPort <= 0) {
            return;
        }

        boolean isNewNode = !lastSeen.containsKey(nodeId);
        membershipView.upsert(nodeId, host, clientPort);
        lastSeen.put(nodeId, System.currentTimeMillis());

        if (isNewNode) {
            LOG.infof(">>> Yeni node bağlandı: %s (%s:%d)", nodeId, host, clientPort);
        }
    }

    private String normaliseHost(String host, InetAddress sourceAddress) {
        if (isUsableHost(host)) {
            return host;
        }

        String networkHost = config.network().host();
        if (isUsableHost(networkHost)) {
            return networkHost;
        }

        if (sourceAddress != null && !sourceAddress.isAnyLocalAddress()) {
            return sourceAddress.getHostAddress();
        }

        String advertised = config.cluster().replication().advertiseHost();
        if (isUsableHost(advertised)) {
            return advertised;
        }

        return InetAddress.getLoopbackAddress().getHostAddress();
    }

    private boolean isUsableHost(String candidate) {
        return candidate != null && !candidate.isBlank() && !Objects.equals(candidate, "0.0.0.0");
    }

    private void pruneExpiredMembers() {
        long now = System.currentTimeMillis();
        long timeout = Math.max(config.cluster().discovery().failureTimeoutMillis(),
                config.cluster().discovery().heartbeatIntervalMillis() * 3);

        lastSeen.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > timeout) {
                membershipView.remove(entry.getKey());
                LOG.warnf("<<< Node zaman aşımına uğradı ve kaldırıldı: %s (son görülme: %dms önce)",
                        entry.getKey(), now - entry.getValue());
                return true;
            }
            return false;
        });
    }

    private NetworkInterface selectInterface() throws SocketException {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            var ni = interfaces.nextElement();
            if (ni.isUp() && ni.supportsMulticast() && !ni.isLoopback()) {
                return ni;
            }
        }
        NetworkInterface loopback = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress());
        if (loopback != null) {
            return loopback;
        }
        throw new SocketException("Uygun multicast arayüzü bulunamadı");
    }

    @PreDestroy
    @Override
    public void close() {
        running = false;
        if (reapTimerId >= 0L) {
            vertx.cancelTimer(reapTimerId);
        }
        if (socket != null) {
            try {
                if (groupAddress != null && networkInterface != null) {
                    socket.leaveGroup(new InetSocketAddress(groupAddress, config.cluster().discovery().multicastPort()),
                            networkInterface);
                }
            } catch (IOException e) {
                LOG.debug("Multicast gruptan ayrılırken hata", e);
            }
            socket.close();
        }
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        membershipView.clear();
    }
}
