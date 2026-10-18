package com.loanplatform.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Eureka service registry for the Docker Compose environment.
 *
 * <p>Services register here and find each other through {@code lb://service-name} URLs, which
 * Spring Cloud LoadBalancer resolves to a registered instance. Kubernetes does not run this
 * application; there, Kubernetes Service DNS does the same job (ADR-0006).
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
