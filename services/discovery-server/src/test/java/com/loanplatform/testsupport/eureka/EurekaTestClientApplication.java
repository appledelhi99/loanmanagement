package com.loanplatform.testsupport.eureka;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * A minimal Spring Boot application that behaves like any service registering with Eureka.
 *
 * <p>It lives outside {@code com.loanplatform.discovery} so the discovery server's component scan
 * never picks it up. Without {@code @EnableEurekaServer} only the Eureka <em>client</em>
 * auto-configuration is active.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class EurekaTestClientApplication {
}
