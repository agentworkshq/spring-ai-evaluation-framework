package dev.agentworkshq.eval.core.report;

import static dev.agentworkshq.eval.core.report.ReportFixtures.baseline;
import static dev.agentworkshq.eval.core.report.ReportFixtures.candidate;
import static dev.agentworkshq.eval.core.report.ReportFixtures.comparison;
import static dev.agentworkshq.eval.core.report.ReportFixtures.done;
import static dev.agentworkshq.eval.core.report.ReportFixtures.resource;
import static dev.agentworkshq.eval.core.report.ReportFixtures.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.compare.Comparison;
import dev.agentworkshq.eval.core.compare.ComparisonConfig;
import dev.agentworkshq.eval.core.compare.RunComparator;
import dev.agentworkshq.eval.core.compare.RunSummary;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.store.StoreFixtures;
import dev.agentworkshq.eval.core.store.StoredCase;
import dev.agentworkshq.eval.core.store.StoredRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportsTest {

    private static final ReportOptions OPTIONS = ReportOptions.defaults();

    @Test
    void consoleReportMatchesTheGoldenFile() {
        String text = ConsoleReport.render(summary(), comparison(), OPTIONS);

        assertThat(text).isEqualTo(resource("console.golden"));
        assertThat(text).doesNotContain("\r").endsWith("\n");
    }

    @Test
    void markdownReportMatchesTheGoldenFile() {
        String text = MarkdownReport.render(summary(), comparison(), OPTIONS.withIncludeRunConfig(true));

        assertThat(text).isEqualTo(resource("markdown.golden"));
        assertThat(text).doesNotContain("\r").endsWith("\n").startsWith(MarkdownReport.MARKER + "\n### ");
    }

    @Test
    void markdownReportWithoutAComparisonMatchesTheGoldenFile() {
        assertThat(MarkdownReport.render(summary(), null, OPTIONS)).isEqualTo(resource("markdown-no-comparison.golden"));
    }

    @Test
    void theRunConfigIsOnlyIncludedWhenAsked() {
        assertThat(MarkdownReport.render(summary(), comparison(), OPTIONS)).doesNotContain("<details>", "label.team");
        assertThat(ConsoleReport.render(summary(), comparison(), OPTIONS)).doesNotContain("label.team");
        assertThat(ConsoleReport.render(summary(), comparison(), OPTIONS.withIncludeRunConfig(true)))
                .contains("Run config", "label.team");
    }

    @Test
    void contentIsRenderedInBothReports() {
        String console = ConsoleReport.render(summary(), comparison(), OPTIONS);
        String markdown = MarkdownReport.render(summary(), comparison(), OPTIONS);

        for (String text : List.of(console, markdown)) {
            assertThat(text).contains("REGRESSION", "FIX", "SCORE_DROP", "COVERAGE_LOST", "NOT_COMPARABLE", "REGRESSED",
                    "TARGET_FAILED", "n/a", "-0.167", "metric faith is not comparable", "coverage lost: case a metric cov");
        }
        assertThat(console).contains("| only_skipped | 0      | 0      | 0     | 1       | n/a   | n/a       | n/a ");
        assertThat(markdown).contains("| `only_skipped` | 0 | 0 | 0 | 1 | n/a | n/a | n/a | n/a | UNCHANGED |");
    }

    @Test
    void aFailingGateAndAPassingGateRenderTheRightHeadline() {
        StoredRun same = baseline();
        Comparison passing = RunComparator.compare(same, same, ComparisonConfig.defaults());

        assertThat(MarkdownReport.render(summary(), comparison(), OPTIONS)).contains("### Evaluation gate: FAILED");
        assertThat(ConsoleReport.render(summary(), comparison(), OPTIONS)).contains("GATE: FAILED");
        assertThat(MarkdownReport.render(RunSummary.of(same), passing, OPTIONS)).contains("### Evaluation gate: PASSED");
        assertThat(ConsoleReport.render(RunSummary.of(same), passing, OPTIONS)).contains("GATE: PASSED");
    }

    @Test
    void comparingARunWithItselfShowsNoChanges() {
        StoredRun same = baseline();
        Comparison c = RunComparator.compare(same, same, ComparisonConfig.defaults());

        String console = ConsoleReport.render(RunSummary.of(same), c, OPTIONS);
        String markdown = MarkdownReport.render(RunSummary.of(same), c, OPTIONS);

        assertThat(console).contains("GATE: PASSED", "Case changes\nnone\n").doesNotContain("REGRESSION", "  - ");
        assertThat(markdown).contains("No violations.", "No case changes.").doesNotContain("REGRESSION", "#### Config changes");
        assertThat(markdown).doesNotContain("REGRESSED");
    }

    @Test
    void notesReportDatasetAndCaseSetDifferencesWithCountsOnly() {
        StoredRun base = new StoredRun("b", "ds", "fp1", "t1", ReportFixtures.T0, ReportFixtures.T0, null,
                List.of(done("a", MetricResult.passed("m", 1, "")), done("x", MetricResult.passed("m", 1, ""))));
        StoredRun cand = new StoredRun("c", "ds", "fp2", "t2", ReportFixtures.T0, ReportFixtures.T0, null,
                List.of(done("a", MetricResult.passed("m", 1, "")), done("y", MetricResult.passed("m", 1, ""))));
        Comparison c = RunComparator.compare(base, cand, ComparisonConfig.defaults().withRequireSameDataset(false));

        String markdown = MarkdownReport.render(RunSummary.of(cand), c, OPTIONS);

        assertThat(markdown).contains("- Dataset content changed.", "- Target changed.", "- Cases only in the baseline: 1",
                "- Cases only in the candidate: 1");
    }

    @Test
    void renderingTwiceAndShufflingTheInputGiveTheSameText() {
        Comparison shuffled = RunComparator.compare(baseline(), candidate(true), ComparisonConfig.defaults());

        assertThat(ConsoleReport.render(summary(), comparison(), OPTIONS)).isEqualTo(ConsoleReport.render(summary(), comparison(), OPTIONS));
        assertThat(MarkdownReport.render(summary(), comparison(), OPTIONS)).isEqualTo(MarkdownReport.render(summary(), comparison(), OPTIONS));
        assertThat(ConsoleReport.render(RunSummary.of(candidate(true)), shuffled, OPTIONS))
                .isEqualTo(ConsoleReport.render(summary(), comparison(), OPTIONS));
        assertThat(MarkdownReport.render(RunSummary.of(candidate(true)), shuffled, OPTIONS))
                .isEqualTo(MarkdownReport.render(summary(), comparison(), OPTIONS));
        assertThat(ComparisonJson.render(shuffled, OPTIONS)).isEqualTo(ComparisonJson.render(comparison(), OPTIONS));
    }

    private static StoredRun manyCases(String id, boolean passing, int count) {
        List<StoredCase> cases = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MetricResult result = passing ? MetricResult.passed("m", 1.0, "ok") : MetricResult.failed("m", 0.0, "no");
            cases.add(done(String.format("case-%04d", i), result));
        }
        return ReportFixtures.run(id, "t", Map.of(), cases);
    }

    private static void assertTablesAreWhole(String text) {
        List<String> group = new ArrayList<>();
        for (String line : (text + "\n.").split("\n")) {
            if (line.startsWith("|")) {
                group.add(line);
                continue;
            }
            if (!group.isEmpty()) {
                long pipes = unescapedPipes(group.get(0));
                assertThat(group).allSatisfy(row -> {
                    assertThat(row).endsWith(" |");
                    assertThat(unescapedPipes(row)).isEqualTo(pipes);
                });
                group.clear();
            }
        }
    }

    private static long unescapedPipes(String row) {
        long count = 0;
        for (int i = 0; i < row.length(); i++) {
            if (row.charAt(i) == '|' && (i == 0 || row.charAt(i - 1) != '\\')) {
                count++;
            }
        }
        return count;
    }

    @Test
    void theMarkdownReportNeverExceedsMaxChars() {
        StoredRun base = manyCases("base", true, 400);
        StoredRun cand = manyCases("cand", false, 400);
        Comparison c = RunComparator.compare(base, cand, ComparisonConfig.defaults());
        ReportOptions options = OPTIONS.withMaxCaseRows(500).withMaxChars(3000);

        String text = MarkdownReport.render(RunSummary.of(cand), c, options);

        assertThat(text.length()).isLessThanOrEqualTo(3000);
        assertThat(text).startsWith(MarkdownReport.MARKER + "\n");
        assertThat(text).matches("(?s).*\\nReport truncated, \\d+ characters left out\\.\\n");
        assertThat(text).contains("| `case-0000` |");
        assertThat(text).doesNotContain("case-0399");
        assertTablesAreWhole(text);
    }

    @Test
    void theCapHoldsAtEveryLimit() {
        StoredRun base = manyCases("base", true, 60);
        StoredRun cand = manyCases("cand", false, 60);
        Comparison c = RunComparator.compare(base, cand, ComparisonConfig.defaults());

        for (int max = 1000; max <= 6000; max += 137) {
            String text = MarkdownReport.render(RunSummary.of(cand), c, OPTIONS.withMaxChars(max).withIncludeRunConfig(true));

            assertThat(text.length()).as("max %d", max).isLessThanOrEqualTo(max);
            assertThat(text).startsWith(MarkdownReport.MARKER);
            assertTablesAreWhole(text);
        }
    }

    @Test
    void aCutInsideTheDetailsBlockNeverLeavesItOpen() {
        StoredRun base = manyCases("base", true, 2);
        Map<String, String> config = new java.util.TreeMap<>();
        for (int i = 0; i < 40; i++) {
            config.put(String.format("setting.%02d", i), "v".repeat(60));
        }
        StoredRun cand = ReportFixtures.run("cand", "t", config, manyCases("x", false, 2).cases());
        Comparison c = RunComparator.compare(base, cand, ComparisonConfig.defaults());

        for (int max = 1000; max <= 3500; max += 97) {
            String text = MarkdownReport.render(RunSummary.of(cand), c, OPTIONS.withMaxChars(max).withIncludeRunConfig(true));

            assertThat(text.length()).isLessThanOrEqualTo(max);
            assertThat(text.contains("<details>")).isEqualTo(text.contains("</details>"));
        }
    }

    @Test
    void maxCaseRowsShowsHowManyMoreThereAre() {
        StoredRun base = manyCases("base", true, 10);
        StoredRun cand = manyCases("cand", false, 10);
        Comparison c = RunComparator.compare(base, cand, ComparisonConfig.defaults());
        ReportOptions options = OPTIONS.withMaxCaseRows(3);

        String console = ConsoleReport.render(RunSummary.of(cand), c, options);
        String markdown = MarkdownReport.render(RunSummary.of(cand), c, options);

        assertThat(console).contains("... and 7 more\n").contains("case-0002").doesNotContain("case-0003");
        assertThat(markdown).contains("... and 7 more\n").contains("`case-0002`").doesNotContain("case-0003");
        assertThat(ConsoleReport.render(RunSummary.of(cand), c, OPTIONS.withMaxCaseRows(10))).doesNotContain("more\n");
    }

    @Test
    void noQuestionAnswerOrChunkTextReachesAnyReport() {
        String marker = "SECRET-MARKER-TEXT";
        StoredRun stored = StoredRun.from(StoreFixtures.evalRunWithMarker(marker));
        Comparison c = RunComparator.compare(stored, stored, ComparisonConfig.defaults());
        RunSummary s = RunSummary.of(stored);
        ReportOptions options = OPTIONS.withIncludeRunConfig(true);

        assertThat(ConsoleReport.render(s, c, options)).doesNotContain(marker);
        assertThat(MarkdownReport.render(s, c, options)).doesNotContain(marker);
        assertThat(ComparisonJson.render(c, options)).doesNotContain(marker);
        assertThat(MarkdownReport.render(s, null, options)).doesNotContain(marker);
    }

    @Test
    void optionsAreValidated() {
        assertThatThrownBy(() -> OPTIONS.withMaxCaseRows(0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OPTIONS.withMaxCaseRows(501)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OPTIONS.withMaxChars(999)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OPTIONS.withMaxChars(65001)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OPTIONS.withMaxFieldChars(9)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> OPTIONS.withMaxFieldChars(201)).isInstanceOf(ValidationException.class);
        assertThat(OPTIONS.maxCaseRows()).isEqualTo(50);
        assertThat(OPTIONS.maxChars()).isEqualTo(60000);
        assertThat(OPTIONS.includeRunConfig()).isFalse();
        assertThat(OPTIONS.maxFieldChars()).isEqualTo(80);
        assertThat(OPTIONS.withMaxFieldChars(200).withMaxChars(65000).withMaxCaseRows(500).withIncludeRunConfig(true))
                .isEqualTo(new ReportOptions(500, 65000, true, 200));
    }

    @Test
    void longFieldsAreCutAtMaxFieldChars() {
        StoredRun cand = ReportFixtures.run("cand", "t".repeat(500), Map.of(), List.of());

        String markdown = MarkdownReport.render(RunSummary.of(cand), null, OPTIONS.withMaxFieldChars(20));

        assertThat(markdown).contains("`" + "t".repeat(17) + "...`").doesNotContain("t".repeat(18));
    }
}
