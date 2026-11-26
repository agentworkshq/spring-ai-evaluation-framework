package dev.agentworkshq.eval.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ReadmeConfigurationTest {

    private static final Path README = Path.of("..", "README.md");
    private static final String HEADING = "## Configuration reference";
    private static final String PREFIX = "eval-framework";
    private static final Pattern NAME = Pattern.compile("`(eval-framework\\.[A-Za-z0-9.-]+)`");
    private static final Pattern ROW =
            Pattern.compile("^\\| `(eval-framework\\.[A-Za-z0-9.-]+)` \\| [^|]+ \\| ([^|]+) \\|.*");

    @Test
    void everyDocumentedPropertyBinds() throws IOException {
        for (String name : documentedNames()) {
            String value = documentedDefaults().get(name);
            bind(Map.of(name, value == null ? "judge-1" : value));
        }
    }

    @Test
    void documentedDefaultsAreTheRealDefaults() throws IOException {
        EvalFrameworkProperties defaults = bind(Map.of());

        documentedDefaults().forEach((name, value) -> {
            if (value != null) {
                assertThat(bind(Map.of(name, value))).as(name).isEqualTo(defaults);
            }
        });
        assertThat(defaults.judge().identifier()).isNull();
    }

    @Test
    void everyPropertyIsDocumented() throws IOException {
        List<String> properties = new ArrayList<>();
        collect(PREFIX, EvalFrameworkProperties.class, properties);

        assertThat(documentedNames()).containsExactlyInAnyOrderElementsOf(properties);
        assertThat(documentedDefaults().keySet()).containsExactlyInAnyOrderElementsOf(properties);
    }

    @Test
    void anUnknownPropertyDoesNotBind() {
        assertThatThrownBy(() -> bind(Map.of("eval-framework.runner.no-such-property", "1")))
                .isInstanceOf(BindException.class);
    }

    private static EvalFrameworkProperties bind(Map<String, String> values) {
        BindHandler handler = new NoUnboundElementsBindHandler(BindHandler.DEFAULT);
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate(PREFIX, Bindable.of(EvalFrameworkProperties.class), handler);
    }

    private static List<String> documentedNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (String row : tableRows()) {
            Matcher matcher = NAME.matcher(row);
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        assertThat(names).as("property names in the table under '%s'", HEADING).isNotEmpty();
        return names;
    }

    // a default of "none" is mapped to null, there is no value to bind
    private static Map<String, String> documentedDefaults() throws IOException {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (String row : tableRows()) {
            Matcher matcher = ROW.matcher(row);
            if (matcher.matches()) {
                String cell = matcher.group(2).strip();
                defaults.put(matcher.group(1), cell.equals("none") ? null : cell.replace("`", ""));
            }
        }
        return defaults;
    }

    private static List<String> tableRows() throws IOException {
        assertThat(Files.isRegularFile(README))
                .as("README.md not found at %s, run the test from the module directory",
                        README.toAbsolutePath().normalize())
                .isTrue();
        List<String> lines = Files.readAllLines(README);
        int start = lines.indexOf(HEADING);
        assertThat(start).as("heading '%s' in README.md", HEADING).isNotNegative();
        return lines.stream()
                .skip(start + 1L)
                .takeWhile(line -> !line.startsWith("## "))
                .filter(line -> line.startsWith("|"))
                .toList();
    }

    private static void collect(String prefix, Class<?> type, List<String> into) {
        for (RecordComponent component : type.getRecordComponents()) {
            String name = prefix + "." + component.getName().replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
            if (component.getType().isRecord()) {
                collect(name, component.getType(), into);
            } else {
                into.add(name);
            }
        }
    }
}
