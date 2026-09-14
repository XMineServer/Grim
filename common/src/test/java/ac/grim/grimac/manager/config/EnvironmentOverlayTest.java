package ac.grim.grimac.manager.config;

import github.scarsz.configuralize.DynamicConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// XMine start - подстановка переменных среды
class EnvironmentOverlayTest {

    @TempDir
    Path dir;

    @Test
    void substitutorFollowsCoreRules() {
        Map<String, String> env = Map.of("SET", "value", "EMPTY", "");

        assertEquals("value", EnvironmentSubstitutor.substitute("${SET}", env::get));
        assertEquals("${UNSET}", EnvironmentSubstitutor.substitute("${UNSET}", env::get) == null
                ? "${UNSET}" : "expanded");
        assertEquals("", EnvironmentSubstitutor.substitute("${EMPTY}", env::get));
        assertEquals("fallback", EnvironmentSubstitutor.substitute("${EMPTY:-fallback}", env::get));
        assertEquals("value", EnvironmentSubstitutor.substitute("${UNSET:-${SET}}", env::get));
        assertEquals("${SET}", EnvironmentSubstitutor.substitute("$${SET}", env::get));
        assertNull(EnvironmentSubstitutor.substitute("${lower}", env::get));
        assertNull(EnvironmentSubstitutor.substitute("no references", env::get));
    }

    @Test
    void publishesExpandedScalarsUnderDottedKeys() throws IOException {
        File file = write("mysql.yml", """
                mysql:
                  host: ${GRIMAC_DATABASE_HOST:-${MYSQL_DATABASE_HOST}}
                  port: ${MYSQL_DATABASE_PORT}
                  password: "${MYSQL_DATABASE_PASSWORD}"
                  database: grim
                """);
        Map<String, String> env = Map.of(
                "MYSQL_DATABASE_HOST", "test-mysql-proxy",
                "MYSQL_DATABASE_PORT", "6033",
                "MYSQL_DATABASE_PASSWORD", "secret");
        DynamicConfig config = new DynamicConfig();

        new EnvironmentOverlay().apply(config, List.of(file), env::get);

        assertEquals("test-mysql-proxy", config.getStringElse("mysql.host", "default"));
        assertEquals(6033, config.getIntElse("mysql.port", 3306));
        assertEquals("secret", config.getStringElse("mysql.password", "default"));
        // A plain value is left to Configuralize's own file map.
        assertEquals("default", config.getStringElse("mysql.database", "default"));
    }

    @Test
    void unsetVariableStaysVisibleInsteadOfBecomingEmpty() throws IOException {
        File file = write("mysql.yml", """
                mysql:
                  password: ${MYSQL_DATABASE_PASSWORD}
                """);
        DynamicConfig config = new DynamicConfig();

        new EnvironmentOverlay().apply(config, List.of(file), name -> null);

        assertEquals("default", config.getStringElse("mysql.password", "default"));
    }

    @Test
    void reloadTakesBackAValueWhoseReferenceWasRemoved() throws IOException {
        File file = write("mysql.yml", """
                mysql:
                  host: ${MYSQL_DATABASE_HOST}
                """);
        Map<String, String> env = new HashMap<>(Map.of("MYSQL_DATABASE_HOST", "from-env"));
        DynamicConfig config = new DynamicConfig();
        EnvironmentOverlay overlay = new EnvironmentOverlay();

        overlay.apply(config, List.of(file), env::get);
        assertEquals("from-env", config.getStringElse("mysql.host", "default"));

        Files.writeString(file.toPath(), "mysql:\n  host: literal\n", StandardCharsets.UTF_8);
        overlay.apply(config, List.of(file), env::get);
        assertEquals("literal", config.getStringElse("mysql.host", "default"));

        Files.writeString(file.toPath(), "mysql: {}\n", StandardCharsets.UTF_8);
        overlay.apply(config, List.of(file), env::get);
        assertEquals("default", config.getStringElse("mysql.host", "default"));
    }

    @Test
    void neverWritesTheExpandedValueToDisk() throws IOException {
        String content = "mysql:\n  password: ${MYSQL_DATABASE_PASSWORD}\n";
        File file = write("mysql.yml", content);

        new EnvironmentOverlay().apply(new DynamicConfig(), List.of(file), Map.of("MYSQL_DATABASE_PASSWORD", "secret")::get);

        String onDisk = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(content, onDisk);
        assertFalse(onDisk.contains("secret"));
    }

    @Test
    void missingOrBrokenFilesAreSkipped() throws IOException {
        File broken = write("broken.yml", "mysql: [unterminated\n");
        File missing = dir.resolve("missing.yml").toFile();
        DynamicConfig config = new DynamicConfig();

        new EnvironmentOverlay().apply(config, List.of(broken, missing), name -> "value");

        assertTrue(config.getStringElse("mysql.host", "default").equals("default"));
    }

    private File write(String name, String content) throws IOException {
        Path path = dir.resolve(name);
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path.toFile();
    }
}
// XMine end - подстановка переменных среды
