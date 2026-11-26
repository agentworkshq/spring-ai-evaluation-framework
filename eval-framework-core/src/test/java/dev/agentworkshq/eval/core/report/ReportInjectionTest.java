package dev.agentworkshq.eval.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentworkshq.eval.core.compare.Comparison;
import dev.agentworkshq.eval.core.compare.ComparisonConfig;
import dev.agentworkshq.eval.core.compare.MetricComparison;
import dev.agentworkshq.eval.core.compare.RunComparator;
import dev.agentworkshq.eval.core.compare.RunSummary;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.store.StoredRun;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReportInjectionTest {

    private static final Set<String> ALLOWED_TAGS = Set.of("<details>", "</details>", "<summary>", "</summary>",
            MarkdownReport.MARKER);
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final String PLACEHOLDER = "placeholder_metric";
    private static final Pattern BAD_CHARS = Pattern.compile(
            "[\\p{Cc}&&[^\\n]]|[\\u202A-\\u202E\\u2066-\\u2069\\u200E\\u200F\\u200B-\\u200D\\u2060\\uFEFF\\u2028\\u2029]");

    static Stream<Arguments> dangerous() {
        return Stream.of(
                Arguments.of("| a | b |", "| a | b |"),
                Arguments.of("line1\nline2", "line1\nline2"),
                Arguments.of("<script>alert(1)</script>", "<script>"),
                Arguments.of("@someone", "@someone"),
                Arguments.of("@org/team", "@org/team"),
                Arguments.of("#123", "#123"),
                Arguments.of("[x](http://evil.example)", "evil.example"),
                Arguments.of("![i](http://evil.example/a.png)", "evil.example"),
                Arguments.of("\u001b[31mred\u001b[0m", "\u001b"),
                Arguments.of("‮evil", "‮"),
                Arguments.of("X".repeat(5000), "XXXXXXXXXX"),
                Arguments.of("a`b``c```d`", "```d"),
                Arguments.of("`start and end`", "`start"));
    }

    private static String config(String text) {
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    // stored runs reject such metric names, so they are put into the comparison records afterwards
    private static Comparison compare(String text) {
        Comparison c = RunComparator.compare(run("base", text, true), run("cand", text, false),
                ComparisonConfig.defaults());
        List<MetricComparison> metrics = c.metrics().stream()
                .map(m -> m.metricName().equals(PLACEHOLDER)
                        ? new MetricComparison(text, m.verdict(), m.tolerance(), m.baseline(), m.candidate())
                        : m)
                .toList();
        List<String> violations = c.violations().stream().map(v -> v.replace(PLACEHOLDER, text)).toList();
        assertThat(metrics).extracting(MetricComparison::metricName).contains(text);
        return new Comparison(c.baselineRunId(), c.candidateRunId(), c.datasetChanged(), c.targetChanged(),
                c.configChanges(), c.onlyInBaseline(), c.onlyInCandidate(), metrics, c.caseChanges(), violations);
    }

    private static StoredRun run(String id, String text, boolean withMetric) {
        MetricResult[] results = withMetric
                ? new MetricResult[] {MetricResult.passed(PLACEHOLDER, 1.0, "ok"), MetricResult.passed("kept", 1.0, "ok")}
                : new MetricResult[] {MetricResult.passed("kept", 1.0, "ok")};
        return ReportFixtures.run(id, text, Map.of("label.note", id.equals("base") ? "plain" : config(text)),
                List.of(ReportFixtures.done("a", results)));
    }

    private static String withoutCodeSpans(String markdown) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < markdown.length()) {
            if (markdown.charAt(i) != '`') {
                out.append(markdown.charAt(i++));
                continue;
            }
            int start = i;
            while (i < markdown.length() && markdown.charAt(i) == '`') {
                i++;
            }
            int fence = i - start;
            int close = -1;
            int j = i;
            while (j < markdown.length()) {
                if (markdown.charAt(j) == '`') {
                    int k = j;
                    while (k < markdown.length() && markdown.charAt(k) == '`') {
                        k++;
                    }
                    if (k - j == fence) {
                        close = k;
                        break;
                    }
                    j = k;
                } else {
                    j++;
                }
            }
            if (close < 0) {
                out.append(markdown, start, i);
            } else {
                i = close;
                out.append("<span>");
            }
        }
        return out.toString();
    }

    @ParameterizedTest
    @MethodSource("dangerous")
    void markdownKeepsDangerousTextInsideCodeSpans(String text, String token) {
        Comparison c = compare(text);
        StoredRun cand = run("cand", text, false);

        String markdown = MarkdownReport.render(RunSummary.of(cand), c, ReportOptions.defaults().withIncludeRunConfig(true));

        assertThat(c.violations()).isNotEmpty();
        assertThat(markdown).isNotEmpty();
        String outside = withoutCodeSpans(markdown).replace("<span>", "");
        assertThat(outside).doesNotContain(token);

        Matcher tags = TAG.matcher(withoutCodeSpans(markdown));
        while (tags.find()) {
            assertThat(ALLOWED_TAGS.contains(tags.group()) || tags.group().equals("<span>"))
                    .as("tag %s", tags.group()).isTrue();
        }
        assertThat(BAD_CHARS.matcher(markdown).find()).isFalse();
        assertPipeCountsAreConsistent(markdown);
    }

    @ParameterizedTest
    @MethodSource("dangerous")
    void consoleHasNoControlCharactersAndIsCapped(String text, String token) {
        Comparison c = compare(text);
        StoredRun cand = run("cand", text, false);

        String console = ConsoleReport.render(RunSummary.of(cand), c, ReportOptions.defaults().withIncludeRunConfig(true));

        assertThat(BAD_CHARS.matcher(console).find()).isFalse();
        assertThat(console).doesNotContain("\r");
        assertThat(console.lines().mapToInt(String::length).max().orElse(0)).isLessThan(2000);
    }

    @ParameterizedTest
    @MethodSource("dangerous")
    void jsonReportHasNoControlCharacters(String text, String token) {
        Comparison c = compare(text);

        String json = ComparisonJson.render(c, ReportOptions.defaults());

        assertThat(BAD_CHARS.matcher(json.replace("\n", "")).find()).isFalse();
        assertThat(json).doesNotContain("\r");
    }

    private static void assertPipeCountsAreConsistent(String markdown) {
        long expected = -1;
        for (String line : (markdown + "\n.").split("\n")) {
            if (!line.startsWith("|")) {
                expected = -1;
                continue;
            }
            long pipes = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '|' && (i == 0 || line.charAt(i - 1) != '\\')) {
                    pipes++;
                }
            }
            if (expected < 0) {
                expected = pipes;
            }
            assertThat(pipes).as(line).isEqualTo(expected);
        }
    }
}
