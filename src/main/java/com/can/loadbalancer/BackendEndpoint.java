package com.can.loadbalancer;

import java.util.Objects;

/**
 * Kümedeki her bir can-cache örneğinin istemci trafiğini kabul ettiği TCP uç
 * noktasını temsil eder. Yük dengeleyici, gelen bağlantıları bu uç noktalara
 * yönlendirmek için {@link ClusterMembershipView} tarafından sağlanan listeleri
 * kullanır.
 */
public record BackendEndpoint(String nodeId, String host, int port) {

    public BackendEndpoint {
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Geçersiz port: " + port);
        }
    }
}
