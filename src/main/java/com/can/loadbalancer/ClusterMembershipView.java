package com.can.loadbalancer;

import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Yük dengeleyicinin proxy edeceği arka uç uç noktalarının anlık görüntüsünü
 * sağlar. Düğümlerin katılımı, ayrılması veya zaman aşımı gibi durumlar,
 * multicast duyurularını dinleyen {@link ClusterAnnouncementListener} tarafından
 * güncellenir ve {@link CanCacheLoadBalancer} bağlantıları bu görünüm üzerinden
 * yönlendirir.
 */
@Singleton
public class ClusterMembershipView {

    private final Map<String, BackendEndpoint> endpoints = new ConcurrentHashMap<>();
    private final ReentrantLock snapshotLock = new ReentrantLock();
    private volatile List<BackendEndpoint> snapshot = List.of();

    public void upsert(String nodeId, String host, int port) {
        if (port <= 0) return;
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(host, "host");

        snapshotLock.lock();
        try {
            endpoints.put(nodeId, new BackendEndpoint(nodeId, host, port));
            snapshot = List.copyOf(endpoints.values());
        } finally {
            snapshotLock.unlock();
        }
    }

    public void remove(String nodeId) {
        if (nodeId == null) {
            return;
        }

        snapshotLock.lock();
        try {
            endpoints.remove(nodeId);
            snapshot = List.copyOf(endpoints.values());
        } finally {
            snapshotLock.unlock();
        }
    }

    public List<BackendEndpoint> snapshot() {
        return snapshot;
    }

    public void clear() {
        snapshotLock.lock();
        try {
            endpoints.clear();
            snapshot = List.of();
        } finally {
            snapshotLock.unlock();
        }
    }
}
