package com.loanplatform.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.loanplatform.testsupport.eureka.EurekaTestClientApplication;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.shared.Application;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Starts the real Eureka server on random ports, then starts a separate Spring Boot application
 * that registers like a normal service. Checks registration, removal on shutdown, the registry
 * API and the management-port split.
 */
@SpringBootTest(
        classes = DiscoveryServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class DiscoveryServerApplicationTest {

    private static final String CLIENT_NAME = "lmp-test-client";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Value("${local.server.port}")
    private int serverPort;

    @Value("${local.management.port}")
    private int managementPort;

    @Autowired
    private PeerAwareInstanceRegistry registry;

    private RestClient api;
    private RestClient management;

    @BeforeEach
    void createClients() {
        api = RestClient.create("http://localhost:" + serverPort);
        management = RestClient.create("http://localhost:" + managementPort);
    }

    @Test
    void registersAServiceAndRemovesItWhenTheServiceStops() {
        String registeredName = CLIENT_NAME.toUpperCase(Locale.ROOT);
        ConfigurableApplicationContext client = startEurekaClient();
        try {
            await().atMost(TIMEOUT).untilAsserted(() -> {
                Application application = registry.getApplication(registeredName);
                assertThat(application).isNotNull();
                assertThat(application.getInstances()).hasSize(1);
                assertThat(application.getInstances().get(0).getStatus())
                        .isEqualTo(InstanceInfo.InstanceStatus.UP);
            });
        } finally {
            client.close();
        }

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Application application = registry.getApplication(registeredName);
            boolean removed = application == null || application.getInstances().isEmpty();
            assertThat(removed).as("instance removed after the client shut down").isTrue();
        });
    }

    @Test
    void servesTheRegistryAsJsonOnTheApiPort() {
        ResponseEntity<String> response = api.get()
                .uri("/eureka/apps")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"applications\"");
    }

    @Test
    void actuatorIsOnlyOnTheManagementPort() {
        assertThat(status(management, "/actuator/health/liveness")).isEqualTo(200);
        assertThat(status(management, "/actuator/health/readiness")).isEqualTo(200);
        assertThat(status(management, "/actuator/info")).isEqualTo(200);
        assertThat(status(management, "/actuator/env")).isEqualTo(404);

        assertThat(status(api, "/actuator")).isEqualTo(404);
    }

    /**
     * Command-line arguments have the highest precedence, and {@code spring.config.name} stops the
     * client from reading the server's application.yml from the shared test classpath.
     */
    private ConfigurableApplicationContext startEurekaClient() {
        return new SpringApplicationBuilder(EurekaTestClientApplication.class).run(
                "--spring.config.name=eureka-test-client",
                "--spring.application.name=" + CLIENT_NAME,
                "--server.port=0",
                "--management.server.port=0",
                "--eureka.client.register-with-eureka=true",
                "--eureka.client.fetch-registry=false",
                "--eureka.client.service-url.defaultZone=http://localhost:" + serverPort + "/eureka/",
                "--eureka.client.initial-instance-info-replication-interval-seconds=1",
                "--eureka.client.instance-info-replication-interval-seconds=1",
                "--eureka.instance.lease-renewal-interval-in-seconds=1",
                "--eureka.instance.hostname=localhost");
    }

    private static int status(RestClient client, String path) {
        ResponseEntity<String> response = client.get()
                .uri(path)
                .retrieve()
                .onStatus(statusCode -> true, (request, clientResponse) -> {
                    // Accept every status; the test asserts on it.
                })
                .toEntity(String.class);
        return response.getStatusCode().value();
    }
}
