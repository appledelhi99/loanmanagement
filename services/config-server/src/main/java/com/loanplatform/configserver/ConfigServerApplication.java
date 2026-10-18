package com.loanplatform.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * Central configuration for the platform.
 *
 * <p>Serves the files in {@code config-repo/} with the {@code native} backend. Clients ask for
 * {@code /{application}/{profile}} and receive the matching property sources, most specific first.
 * The repository contains no secrets; values such as passwords are {@code ${ENV_VAR}} placeholders
 * that each service resolves from its own environment (ADR-0017).
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
