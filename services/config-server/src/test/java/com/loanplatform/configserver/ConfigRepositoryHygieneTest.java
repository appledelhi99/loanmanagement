package com.loanplatform.configserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards for {@code config-repo/} (ADR-0017): the files parse, and secrets appear only as
 * {@code ${ENV_VAR}} placeholders, never as literal values.
 */
class ConfigRepositoryHygieneTest {

    private static final Pattern SECRET_ENTRY = Pattern.compile(
            "(?i)^\\s*[\\w.-]*(password|secret|token|credential|api-key|apikey|private-key)[\\w.-]*\\s*:\\s*(.*)$");

    @Test
    void containsTheSharedFiles() {
        assertThat(repository().resolve("application.yml")).isRegularFile();
        assertThat(repository().resolve("application-docker.yml")).isRegularFile();
        assertThat(repository().resolve("application-kubernetes.yml")).isRegularFile();
    }

    @Test
    void everyFileIsValidYaml() throws IOException {
        for (Path file : yamlFiles()) {
            assertThatCode(() -> parse(file))
                    .as("YAML in %s", file.getFileName())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void secretsAreOnlyEnvironmentPlaceholders() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : yamlFiles()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                Matcher matcher = SECRET_ENTRY.matcher(lines.get(index));
                if (matcher.matches()) {
                    String value = withoutComment(matcher.group(2));
                    boolean nestedKey = value.isEmpty();
                    boolean placeholder = value.startsWith("${") && value.endsWith("}");
                    if (!nestedKey && !placeholder) {
                        violations.add(file.getFileName() + ":" + (index + 1) + " -> " + lines.get(index).trim());
                    }
                }
            }
        }
        assertThat(violations)
                .as("secret-looking keys must use ${ENV_VAR} placeholders")
                .isEmpty();
    }

    private static String withoutComment(String value) {
        int comment = value.indexOf(" #");
        String result = comment >= 0 ? value.substring(0, comment) : value;
        return result.trim();
    }

    private static void parse(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            for (Object document : new Yaml().loadAll(input)) {
                assertThat(document).isInstanceOf(Map.class);
            }
        }
    }

    private static List<Path> yamlFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(repository())) {
            stream.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(files::add);
        }
        assertThat(files).isNotEmpty();
        return files;
    }

    private static Path repository() {
        return Path.of(System.getProperty("basedir", "."), "..", "..", "config-repo").normalize();
    }
}
