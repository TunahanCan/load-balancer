package com.can.loadbalancer.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.List;
import java.util.Optional;

/**
 * Yük dengeleyici modülünün ihtiyaç duyduğu yapılandırma parametrelerini
 * tanımlar. Varsayılanlar can-cache uygulamasındaki karşılıklarıyla uyumludur
 * ve aynı {@code application.properties} dosyası üzerinden yönetilebilir.
 */
@ConfigMapping(prefix = "app")
public interface LoadBalancerConfig {

    LoadBalancer loadBalancer();

    Cluster cluster();

    Network network();

    interface LoadBalancer {

        @WithDefault("true")
        boolean enabled();

        @WithDefault("0.0.0.0")
        String host();

        @WithDefault("12000")
        int port();

        @WithDefault("128")
        int backlog();

        @WithDefault("3000")
        int connectTimeoutMillis();

        /**
         * Statik node listesi. Multicast çalışmadığında bu listedeki node'lar
         * otomatik olarak eklenir. Format: host:port (örn: 127.0.0.1:11211)
         */
        Optional<List<String>> staticNodes();
    }

    interface Cluster {

        Discovery discovery();

        Replication replication();
    }

    interface Discovery {

        @WithDefault("230.0.0.1")
        String multicastGroup();

        @WithDefault("45565")
        int multicastPort();

        @WithDefault("5000")
        long heartbeatIntervalMillis();

        @WithDefault("15000")
        long failureTimeoutMillis();
    }

    interface Replication {

        @WithDefault("127.0.0.1")
        String advertiseHost();
    }

    interface Network {

        @WithDefault("0.0.0.0")
        String host();

        @WithDefault("11211")
        int port();

        @WithDefault("HELLO")
        String agreementPackMessage();
    }
}
