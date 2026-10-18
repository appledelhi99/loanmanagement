package com.loanplatform.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Starts the real config server on random ports and checks what clients will see.
 *
 * <p>The server reads the copy of {@code config-repo/} that the build packages into the jar,
 * so these tests also prove the packaging step works.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class ConfigServerApplicationTest {

    private static final Pattern PROFILE_FILE = Pattern.compile("application-(.+)\\.yml");

    @Value("${local.server.port}")
    private int serverPort;

    @Value("${local.management.port}")
    private int managementPort;

    private RestClient api;
    private RestClient management;

    @BeforeEach
    void createClients() {
        api = RestClient.create("http://localhost:" + serverPort);
        management = RestClient.create("http://localhost:" + managementPort);
    }

    @Test
    void servesTheProfileSourceBeforeTheSharedDefaults() {
        EnvironmentView environment = fetch("loan-service", "docker");

        assertThat(environment.name()).isEqualTo("loan-service");
        assertThat(environment.profiles()).containsExactly("docker");
        List<String> names = sourceNames(environment);
        assertThat(names).hasSize(2);
        assertThat(names.get(0)).contains("application-docker.yml");
        assertThat(names.get(1)).contains("application.yml").doesNotContain("application-docker");
    }

    @Test
    void dockerProfileTurnsEurekaOnAndKubernetesProfileTurnsItOff() {
        assertThat(firstValue(fetch("loan-service", "docker"), "eureka.client.enabled")).isEqualTo(true);
        assertThat(firstValue(fetch("loan-service", "kubernetes"), "eureka.client.enabled")).isEqualTo(false);
        assertThat(firstValue(fetch("loan-service", "default"), "eureka.client.enabled")).isEqualTo(false);
    }

    @Test
    void sharedDefaultsReachEveryClient() {
        EnvironmentView environment = fetch("customer-service", "default");

        assertThat(firstValue(environment, "spring.threads.virtual.enabled")).isEqualTo(true);
        assertThat(firstValue(environment, "management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus");
    }

    @Test
    void servesEveryProfileFileOfTheRepository() throws IOException {
        List<String> profileFiles = profileFilesInRepository();
        assertThat(profileFiles).contains("application-docker.yml", "application-kubernetes.yml");

        for (String fileName : profileFiles) {
            Matcher matcher = PROFILE_FILE.matcher(fileName);
            assertThat(matcher.matches()).isTrue();
            String profile = matcher.group(1);

            List<String> names = sourceNames(fetch("any-service", profile));

            assertThat(names)
                    .as("property sources served for profile '%s'", profile)
                    .anySatisfy(name -> assertThat(name).contains(fileName));
        }
    }

    @Test
    void actuatorIsOnlyOnTheManagementPort() {
        assertThat(status(management, "/actuator")).isEqualTo(200);
        assertThat(status(management, "/actuator/health/liveness")).isEqualTo(200);
        assertThat(status(management, "/actuator/health/readiness")).isEqualTo(200);

        assertThat(status(api, "/actuator")).isEqualTo(404);
    }

    @Test
    void exposesOnlyHealthAndInfo() {
        assertThat(status(management, "/actuator/info")).isEqualTo(200);
        assertThat(status(management, "/actuator/env")).isEqualTo(404);
        assertThat(status(management, "/actuator/configprops")).isEqualTo(404);
        assertThat(status(management, "/actuator/beans")).isEqualTo(404);
    }

    private EnvironmentView fetch(String application, String profile) {
        EnvironmentView environment = api.get()
                .uri("/{application}/{profile}", application, profile)
                .retrieve()
                .body(EnvironmentView.class);
        assertThat(environment).isNotNull();
        return environment;
    }

    private static List<String> sourceNames(EnvironmentView environment) {
        List<String> names = new ArrayList<>();
        for (PropertySourceView source : environment.propertySources()) {
            names.add(source.name());
        }
        return names;
    }

    /** Returns the value a client would get: the first (most specific) source that defines the key wins. */
    private static Object firstValue(EnvironmentView environment, String key) {
        for (PropertySourceView source : environment.propertySources()) {
            if (source.source().containsKey(key)) {
                return source.source().get(key);
            }
        }
        return null;
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

    private static List<String> profileFilesInRepository() throws IOException {
        Path repository = Path.of(System.getProperty("basedir", "."), "..", "..", "config-repo").normalize();
        List<String> fileNames = new ArrayList<>();
        try (Stream<Path> files = Files.list(repository)) {
            files.map(file -> file.getFileName().toString())
                    .filter(name -> PROFILE_FILE.matcher(name).matches())
                    .sorted()
                    .forEach(fileNames::add);
        }
        return fileNames;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnvironmentView(String name, List<String> profiles, List<PropertySourceView> propertySources) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PropertySourceView(String name, Map<String, Object> source) {
    }
}
