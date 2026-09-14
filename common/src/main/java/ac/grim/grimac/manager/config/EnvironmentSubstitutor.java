package ac.grim.grimac.manager.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

// XMine start - подстановка переменных среды
/**
 * Expands environment variable references such as {@code ${DB_HOST}} inside configuration values.
 *
 * <p>This is a port of {@code io.papermc.paper.configuration.EnvironmentSubstitutor} from the XMine
 * Paper fork, kept deliberately identical in behaviour (the same copy lives in the XMine
 * SkinsRestorer fork). A game node reads most plugin configs through Bukkit's
 * {@code YamlConfiguration}, which carries that class; Grim reads its own through Configuralize and
 * never touches it, so without this copy the same server would have two different substitution
 * dialects. It is a copy rather than a dependency because Grim also builds for Fabric, where
 * paper-api does not exist.
 *
 * <h2>Syntax</h2>
 * <ul>
 *     <li>{@code ${NAME}} - replaced by the value of the environment variable {@code NAME}.
 *     If the variable is <em>not set</em>, the reference is left in place verbatim. If it is
 *     set but empty, it expands to the empty string.</li>
 *     <li>{@code ${NAME:-fallback}} - replaced by the value of {@code NAME} when it is set and
 *     non-empty, and by {@code fallback} otherwise. This mirrors the POSIX shell operator of the
 *     same name, including its treatment of an empty value as "not set". The fallback text is
 *     itself expanded, so {@code ${A:-${B:-none}}} works.</li>
 *     <li>{@code $${NAME}} - an escape: expands to the literal text {@code ${NAME}} and the
 *     variable is never looked up.</li>
 * </ul>
 *
 * <h2>Why these rules</h2>
 * Every ambiguous case is resolved in favour of leaving the text alone:
 * <ul>
 *     <li><b>Names are upper case only</b> ({@code [A-Z_][A-Z0-9_]*}): environment variables are
 *     upper case by convention, placeholders that plugins define for themselves are
 *     overwhelmingly lower case ({@code %player%}, {@code ${player}}).</li>
 *     <li><b>An unset variable is left as written</b>, so a forgotten variable names itself in
 *     the failure instead of becoming a confusing empty value. Use {@code ${NAME:-}} to ask for
 *     an empty string explicitly.</li>
 *     <li><b>{@code $$} is the escape</b>, matching Docker Compose.</li>
 *     <li><b>Substituted values are never rescanned</b>, which rules out expansion loops and one
 *     variable's content reaching into another.</li>
 * </ul>
 *
 * <p>This class never logs, and never places a variable's value into an exception message.
 *
 * @see EnvironmentOverlay
 */
public final class EnvironmentSubstitutor {

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");

    /**
     * Guards against a fallback chain such as {@code ${A:-${A:-${A:-...}}}} nested deeply enough
     * to matter. Real configurations never come close.
     */
    private static final int MAX_DEPTH = 16;

    private EnvironmentSubstitutor() {
    }

    /**
     * Expands every environment variable reference in the given text.
     *
     * @param text the raw configuration text
     * @param environment lookup for variable values, normally {@code System::getenv}; it must
     *     return {@code null} for a variable that is not set
     * @return the expanded text, or {@code null} if the text contains nothing to expand
     */
    public static @Nullable String substitute(@NotNull String text, @NotNull UnaryOperator<@Nullable String> environment) {
        return substitute(text, environment, 0);
    }

    private static @Nullable String substitute(String text, UnaryOperator<String> environment, int depth) {
        if (depth > MAX_DEPTH || text.indexOf('$') < 0) {
            return null; // fast path: the overwhelming majority of configuration values
        }

        final int length = text.length();
        StringBuilder result = null;
        int index = 0;
        while (index < length) {
            final char current = text.charAt(index);
            if (current != '$') {
                if (result != null) {
                    result.append(current);
                }
                index++;
                continue;
            }

            // "$${NAME}" -> literal "${NAME}", with the body copied over untouched.
            if (index + 2 < length && text.charAt(index + 1) == '$' && text.charAt(index + 2) == '{') {
                final int close = matchingBrace(text, index + 2);
                if (close >= 0) {
                    if (result == null) {
                        result = new StringBuilder(length + 16).append(text, 0, index);
                    }
                    result.append(text, index + 1, close + 1);
                    index = close + 1;
                    continue;
                }
            }

            if (index + 1 < length && text.charAt(index + 1) == '{') {
                final int close = matchingBrace(text, index + 1);
                if (close >= 0) {
                    final String replacement = resolve(text.substring(index + 2, close), environment, depth);
                    if (replacement != null) {
                        if (result == null) {
                            result = new StringBuilder(length + 16).append(text, 0, index);
                        }
                        result.append(replacement);
                        index = close + 1;
                        continue;
                    }
                }
            }

            // A lone '$', an unterminated '${', a lower-case name, an unset variable: leave it be.
            if (result != null) {
                result.append(current);
            }
            index++;
        }

        return result == null ? null : result.toString();
    }

    /**
     * Resolves the body of a single {@code ${...}} reference.
     *
     * @return the replacement text, or {@code null} to leave the reference in place verbatim
     */
    private static @Nullable String resolve(String body, UnaryOperator<String> environment, int depth) {
        final int separator = body.indexOf(":-");
        final String name = separator < 0 ? body : body.substring(0, separator);
        if (!VARIABLE_NAME.matcher(name).matches()) {
            return null;
        }

        final String value = environment.apply(name);
        if (separator < 0) {
            return value; // null - not set - leaves "${NAME}" untouched
        }
        if (value != null && !value.isEmpty()) {
            return value;
        }

        final String fallback = body.substring(separator + 2);
        final String expanded = substitute(fallback, environment, depth + 1);
        return expanded != null ? expanded : fallback;
    }

    /**
     * Finds the {@code '}'} matching the {@code '{'} at {@code open}, counting nested braces so
     * that {@code ${A:-${B}}} and {@code ${A:-{literal}}} both parse.
     *
     * @return the index of the closing brace, or {@code -1} if the reference is unterminated
     */
    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            final char current = text.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }
}
// XMine end - подстановка переменных среды
