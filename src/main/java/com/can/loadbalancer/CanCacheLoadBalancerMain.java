package com.can.loadbalancer;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.jboss.logging.Logger;

/**
 * can-cache yük dengeleyicisini tek başına ayağa kaldırmak için Quarkus ana giriş noktası.
 */
@QuarkusMain
public class CanCacheLoadBalancerMain implements QuarkusApplication
{

    private static final Logger LOG = Logger.getLogger(CanCacheLoadBalancerMain.class);

    static void main(String... args) {
        Quarkus.run(CanCacheLoadBalancerMain.class, args);
    }

    @Override
    public int run(String... args) {
        LOG.info("can-cache-load-balancer starting...");
        Quarkus.waitForExit();
        return 0;
    }
}
