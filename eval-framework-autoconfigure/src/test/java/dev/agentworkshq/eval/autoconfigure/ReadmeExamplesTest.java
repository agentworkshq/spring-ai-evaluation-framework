package dev.agentworkshq.eval.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ReadmeExamplesTest {

    private static final Path README = Path.of("..", "README.md");
    private static final Path SAMPLE_TESTS = Path.of("..", "eval-framework-sample", "src", "test", "java");
    private static final Pattern MARKER = Pattern.compile("<!--\\s*example:\\s*(\\S+)\\s*-->");

    @Test
    void everyMarkedReadmeBlockIsPartOfASampleTest() throws IOException {
        Map<String, String> examples = readmeExamples();

        // removing a comment must not silently switch the check off
        assertThat(examples).containsKeys("quickstart", "judge");
        for (Map.Entry<String, String> example : examples.entrySet()) {
            String name = example.getKey();
            assertThat(normalize(region(name)))
                    .as("README.md block '%s' differs from the region between '// README-BEGIN %s' and "
                            + "'// README-END %s' in the sample tests", name, name, name)
                    .contains(normalize(example.getValue()));
        }
    }

    private static Map<String, String> readmeExamples() throws IOException {
        assertThat(Files.isRegularFile(README))
                .as("README.md not found at %s, run the test from the module directory",
                        README.toAbsolutePath().normalize())
                .isTrue();
        List<String> lines = Files.readAllLines(README);
        Map<String, String> examples = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher marker = MARKER.matcher(lines.get(i).strip());
            if (!marker.matches()) {
                continue;
            }
            String name = marker.group(1);
            int fence = i + 1;
            while (fence < lines.size() && lines.get(fence).isBlank()) {
                fence++;
            }
            assertThat(fence < lines.size() && lines.get(fence).strip().equals("```java"))
                    .as("the comment for example '%s' in README.md must be followed by a java block", name)
                    .isTrue();
            List<String> block = new ArrayList<>();
            int end = fence + 1;
            while (end < lines.size() && !lines.get(end).strip().equals("```")) {
                block.add(lines.get(end++));
            }
            assertThat(end).as("the java block of example '%s' in README.md is not closed", name).isLessThan(lines.size());
            assertThat(normalize(String.join("\n", block))).as("README.md block '%s' is empty", name).isNotEmpty();
            assertThat(examples.put(name, String.join("\n", block)))
                    .as("example '%s' appears twice in README.md", name).isNull();
        }
        return examples;
    }

    private static String region(String name) throws IOException {
        assertThat(Files.isDirectory(SAMPLE_TESTS))
                .as("sample test sources not found at %s", SAMPLE_TESTS.toAbsolutePath().normalize())
                .isTrue();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(SAMPLE_TESTS)) {
            sources = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        List<String> regions = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source).stream().map(String::strip).toList();
            int begin = lines.indexOf("// README-BEGIN " + name);
            if (begin >= 0) {
                int end = lines.indexOf("// README-END " + name);
                assertThat(end).as("'// README-END %s' after the begin marker in %s", name, source).isGreaterThan(begin);
                regions.add(String.join("\n", lines.subList(begin + 1, end)));
            }
        }
        assertThat(regions)
                .as("README.md block '%s' needs exactly one '// README-BEGIN %s' region in %s", name, name,
                        SAMPLE_TESTS.toAbsolutePath().normalize())
                .hasSize(1);
        return regions.get(0);
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
