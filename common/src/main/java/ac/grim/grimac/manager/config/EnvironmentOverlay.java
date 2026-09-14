package ac.grim.grimac.manager.config;

import github.scarsz.configuralize.DynamicConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

// XMine start - подстановка переменных среды
/**
 * Applies {@link EnvironmentSubstitutor} to the values Configuralize has loaded.
 *
 * <p>Configuralize parses each file into its own map and offers no hook into that step, but
 * {@link DynamicConfig#dget(String)} consults its runtime values before any file. So after
 * {@code loadAll()} every source file is parsed again here, and each string scalar that contains an
 * expandable reference is published as a runtime value under its full dotted key:
 *
 * <pre>{@code
 * mysql:
 *   host: ${GRIMAC_DATABASE_HOST:-${MYSQL_DATABASE_HOST}}   # -> runtime value "mysql.host"
 *   port: ${GRIMAC_DATABASE_PORT:-${MYSQL_DATABASE_PORT}}   # read back with getIntElse
 * }</pre>
 *
 * <h2>Types</h2>
 * Expanded values are always strings. That is enough for Grim: every typed getter of
 * Configuralize converts through the value's string form ({@code getIntElse} and friends parse it,
 * {@code getBooleanElse} accepts {@code true/false/yes/no/on/off/1/0}). Unlike the Paper fork,
 * quoting therefore does not decide the type.
 *
 * <h2>Limits</h2>
 * Only scalars reached through mappings are expanded. Values inside lists are not, and neither are
 * scalars read as part of a whole map ({@code getMapElse("database.routing")}): both are served
 * from Configuralize's own maps, which runtime values do not reach.
 *
 * <p>Nothing is ever written to disk, so an expanded secret cannot end up in a file.
 */
final class EnvironmentOverlay {

    /**
     * Keys this overlay has published, so that a reload can take back a value whose reference was
     * removed from the file. Configuralize has no way to delete a runtime value.
     */
    private final Set<String> published = new HashSet<>();

    void apply(@NotNull DynamicConfig config, @NotNull List<File> files, @NotNull UnaryOperator<@Nullable String> environment) {
        Map<String, Object> scalars = new HashMap<>();
        for (File file : files) {
            Object root = parse(file);
            if (root instanceof Map<?, ?> map) {
                flatten(map, "", scalars);
            }
        }

        Set<String> current = new HashSet<>();
        for (Map.Entry<String, Object> entry : scalars.entrySet()) {
            if (!(entry.getValue() instanceof String text)) {
                continue;
            }
            String expanded = EnvironmentSubstitutor.substitute(text, environment);
            if (expanded != null) {
                config.setRuntimeValue(entry.getKey(), expanded);
                current.add(entry.getKey());
            }
        }

        for (String key : published) {
            if (!current.contains(key)) {
                // The file value again, or null - which the getters treat as missing - if the key is gone.
                Object fileValue = scalars.get(key);
                config.setRuntimeValue(key, fileValue);
                if (fileValue != null) {
                    // Still shadowing the file: a later reload must be able to take this copy back too.
                    current.add(key);
                }
            }
        }
        published.clear();
        published.addAll(current);
    }

    private static @Nullable Object parse(@NotNull File file) {
        if (!file.isFile()) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (IOException | YAMLException e) {
            // Configuralize has just loaded this file and reports its own errors; nothing to add.
            return null;
        }
    }

    private static void flatten(@NotNull Map<?, ?> map, @NotNull String prefix, @NotNull Map<String, Object> out) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flatten(child, key + ".", out);
            } else {
                out.put(key, entry.getValue());
            }
        }
    }
}
// XMine end - подстановка переменных среды
