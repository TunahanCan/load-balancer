package com.can.loadbalancer;

import com.can.loadbalancer.config.LoadBalancerConfig;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP tabanlı yük dengeleyici bileşenidir. Gelen istemci bağlantılarını canlı
 * can-cache düğümlerine round-robin stratejisiyle iletir.
 */
@Startup
@Singleton
public class CanCacheLoadBalancer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CanCacheLoadBalancer.class);
    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final Vertx vertx;
    private final ClusterMembershipView membershipView;
    private final LoadBalancerConfig.LoadBalancer config;
    private final boolean enabled;
    private final AtomicInteger rrCounter = new AtomicInteger();

    private NetServer netServer;
    private NetClient netClient;

    @Inject
    public CanCacheLoadBalancer(Vertx vertx,
                                ClusterMembershipView membershipView,
                                LoadBalancerConfig config) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.membershipView = Objects.requireNonNull(membershipView, "membershipView");
        Objects.requireNonNull(config, "config");
        this.config = config.loadBalancer();
        this.enabled = this.config.enabled();
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            LOG.info("can-cache-load-balancer devre dışı bırakıldı (app.load-balancer.enabled=false)");
            return;
        }

        NetServerOptions serverOptions = new NetServerOptions()
                .setHost(config.host())
                .setPort(config.port())
                .setTcpNoDelay(true)
                .setReuseAddress(true)
                .setAcceptBacklog(Math.max(1, config.backlog()));

        netServer = vertx.createNetServer(serverOptions);

        NetClientOptions clientOptions = new NetClientOptions()
                .setConnectTimeout(Math.max(100, config.connectTimeoutMillis()))
                .setTcpNoDelay(true)
                .setReuseAddress(true);
        netClient = vertx.createNetClient(clientOptions);

        netServer.connectHandler(this::handleClientConnection);

        try {
            netServer.listen().toCompletionStage().toCompletableFuture().join();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Yük dengeleyici portu dinlenemedi", e);
        }

        LOG.infof("can-cache-load-balancer %s:%d adresinde dinlemede", config.host(), netServer.actualPort());
    }

    private void handleClientConnection(NetSocket clientSocket) {
        List<BackendEndpoint> endpoints = membershipView.snapshot();
        if (endpoints.isEmpty()) {
            LOG.warn("Aktif can-cache düğümü bulunamadı, bağlantı sonlandırılıyor");
            clientSocket.close();
            return;
        }

        int startIndex = rrCounter.getAndIncrement();
        attemptBackendConnection(clientSocket, endpoints, startIndex, 0);
    }

    private void attemptBackendConnection(NetSocket clientSocket,
                                          List<BackendEndpoint> endpoints,
                                          int startIndex,
                                          int attempt) {
        if (endpoints.isEmpty() || attempt >= endpoints.size()) {
            LOG.warn("Uygun backend bulunamadı, tüm adaylar denendi");
            clientSocket.close();
            return;
        }

        int index = Math.floorMod(startIndex + attempt, endpoints.size());
        BackendEndpoint backend = endpoints.get(index);

        netClient.connect(backend.port(), backend.host(), ar -> {
            if (ar.failed()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugf(ar.cause(), "Backend %s:%d bağlantısı kurulamadı",
                            backend.host(), backend.port());
                }
                attemptBackendConnection(clientSocket, endpoints, startIndex, attempt + 1);
                return;
            }

            NetSocket backendSocket = ar.result();
            backendSocket.handler(clientSocket::write);
            backendSocket.exceptionHandler(e -> {
                if (LOG.isDebugEnabled()) {
                    LOG.debugf(e, "Backend soket hatası %s:%d", backend.host(), backend.port());
                }
                backendSocket.close();
            });
            backendSocket.closeHandler(_ -> clientSocket.close());

            clientSocket.handler(backendSocket::write);
            clientSocket.exceptionHandler(e -> {
                if (LOG.isDebugEnabled()) {
                    LOG.debugf(e, "İstemci soket hatası %s", clientSocket.remoteAddress());
                }
                clientSocket.close();
            });
            clientSocket.closeHandler(_ -> backendSocket.close());
        });
    }


    @PreDestroy
    @Override
    public void close() {
        if (!enabled) return;

        try {
            if (netServer != null) {
                netServer.close().toCompletionStage().toCompletableFuture()
                        .get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            LOG.warn("NetServer kapatılırken zaman aşımı veya hata", e);
        }

        try {
            if (netClient != null) {
                netClient.close().toCompletionStage().toCompletableFuture()
                        .get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            LOG.warn("NetClient kapatılırken zaman aşımı veya hata", e);
        }
    }
}
