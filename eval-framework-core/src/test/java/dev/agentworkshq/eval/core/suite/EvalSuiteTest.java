package dev.agentworkshq.eval.core.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.compare.ChangeKind;
import dev.agentworkshq.eval.core.dataset.DatasetLimits;
import dev.agentworkshq.eval.core.dataset.DatasetLoader;
import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.report.ReportWriter;
import dev.agentworkshq.eval.core.runner.EvalRunner;
import dev.agentworkshq.eval.core.runner.RunConfig;
import dev.agentworkshq.eval.core.store.FileRunStore;
import dev.agentworkshq.eval.core.store.StoreLimits;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvalSuiteTest {

    private static final String MARKER = "SECRET-MARKER-TEXT";

    @TempDir
    Path dir;

    Path datasets;
    Path storeDir;
    Path reportDir;
    Path baselineDir;

    @BeforeEach
    void setUp() throws IOException {
        datasets = Files.createDirectory(dir.resolve("datasets"));
        storeDir = dir.resolve("runs");
        reportDir = dir.resolve("reports");
        baselineDir = dir.resolve("baselines");
        writeDataset("question");
    }

    private void writeDataset(String questionPrefix) throws IOException {
        Files.writeString(datasets.resolve("sample.yaml"), """
                name: sample
                cases:
                  - id: a
                    question: %s a
                    expectedAnswer: %s expected
                    attributes: {note: %s}
                  - id: b
                    question: %s b
                  - id: c
                    question: %s c
                """.formatted(questionPrefix, questionPrefix, questionPrefix, questionPrefix, questionPrefix));
    }

    private EvalSuite suite() {
        return new EvalSuite(new DatasetLoader(datasets, DatasetLimits.defaults()), new EvalRunner(),
                new FileRunStore(storeDir, StoreLimits.defaults()), baselineDir, StoreLimits.defaults(),
                new ReportWriter(reportDir));
    }

    private static EvalTarget target(Function<EvalCase, TargetOutput> body) {
        return new EvalTarget() {
            @Override
            public String name() {
                return "fake-target";
            }

            @Override
            public TargetOutput run(EvalCase evalCase) {
                return body.apply(evalCase);
            }
        };
    }

    private static TargetOutput answer(String text) {
        return new TargetOutput(text, true, List.of(), List.of(), Duration.ZERO, Map.of());
    }

    private static EvalTarget good() {
        return target(c -> answer("good " + c.id()));
    }

    private static Metric metric(String name, Function<TargetOutput, MetricResult> body) {
        return new Metric() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
                return body.apply(output);
            }
        };
    }

    private static Metric m() {
        return metric("m", o -> o.answer().startsWith("good") ? MetricResult.passed("m", 1.0, "ok")
                : MetricResult.failed("m", 0.0, "no"));
    }

    private static EvalRequest request(String runId) {
        return EvalRequest.defaults().withRunConfig(RunConfig.defaults().withRunId(runId));
    }

    private void makeBaseline(String id) throws IOException {
        suite().evaluate("sample.yaml", good(), List.of(m()), request(id).withWriteReports(false));
        Files.createDirectories(baselineDir);
        Files.copy(storeDir.resolve("run-" + id + ".json"), baselineDir.resolve("run-" + id + ".json"));
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aFullPassWithABaselineWritesTheRunFileAndBothReports() throws IOException {
        makeBaseline("base");

        EvalOutcome outcome = suite().evaluate("sample.yaml", good(), List.of(m()),
                request("cand").withBaselineRunId("base"));

        assertThat(outcome.passesGate()).isTrue();
        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.comparison()).isNotNull();
        assertThat(outcome.comparison().caseChanges()).isEmpty();
        assertThat(outcome.baselineRunId()).isEqualTo("base");
        assertThat(outcome.summary().runId()).isEqualTo("cand");
        assertThat(outcome.reportFiles()).containsExactly("eval-report-cand.md", "eval-report-cand.json");
        assertThat(names(storeDir)).containsExactly("run-base.json", "run-cand.json");
        assertThat(names(reportDir)).containsExactly("eval-report-cand.json", "eval-report-cand.md");
        assertThat(Files.readString(reportDir.resolve("eval-report-cand.md"))).isEqualTo(outcome.markdownReport());
        assertThat(outcome.consoleReport()).contains("GATE: PASSED");
        assertThat(outcome.markdownReport()).contains("### Evaluation gate: PASSED");
    }

    @Test
    void aRegressionAgainstTheBaselineFailsTheGate() throws IOException {
        makeBaseline("base");
        EvalTarget worse = target(c -> answer(c.id().equals("b") ? "bad" : "good " + c.id()));

        EvalOutcome outcome = suite().evaluate("sample.yaml", worse, List.of(m()), request("cand").withBaselineRunId("base"));

        assertThat(outcome.passesGate()).isFalse();
        assertThat(outcome.comparison().caseChanges()).extracting(c -> c.kind()).contains(ChangeKind.REGRESSION);
        assertThat(outcome.violations()).isNotEmpty().isSorted();
        assertThat(outcome.consoleReport()).contains("GATE: FAILED");
        assertThat(Files.readString(reportDir.resolve("eval-report-cand.json"))).contains("\"passesGate\": false");
    }

    @Test
    void withoutABaselineNoComparisonIsMade() throws IOException {
        EvalOutcome outcome = suite().evaluate("sample.yaml", good(), List.of(m()), request("solo"));

        assertThat(outcome.comparison()).isNull();
        assertThat(outcome.baselineRunId()).isNull();
        assertThat(outcome.passesGate()).isTrue();
        assertThat(outcome.consoleReport()).contains("no baseline");
        assertThat(outcome.markdownReport()).contains("no baseline");
        assertThat(outcome.reportFiles()).containsExactly("eval-report-solo.md");
        assertThat(names(baselineDir.getParent())).doesNotContain("baselines");
    }

    @Test
    void aMissingBaselineDirectoryIsAnErrorAndIsNeverCreated() throws IOException {
        assertThatThrownBy(() -> suite().evaluate("sample.yaml", good(), List.of(m()), request("r").withBaselineRunId("base")))
                .isInstanceOf(BaseException.class)
                .extracting(e -> ((BaseException) e).getErrorMessage()).isEqualTo(ErrorMessages.EVAL_E027);

        assertThat(baselineDir).doesNotExist();
        assertThat(storeDir).isEmptyDirectory();
    }

    @Test
    void aMissingBaselineFileIsAnError() throws IOException {
        Files.createDirectory(baselineDir);

        assertThatThrownBy(() -> suite().evaluate("sample.yaml", good(), List.of(m()), request("r").withBaselineRunId("base")))
                .isInstanceOf(BaseException.class)
                .extracting(e -> ((BaseException) e).getErrorMessage()).isEqualTo(ErrorMessages.EVAL_E026);

        assertThat(baselineDir).isEmptyDirectory();
    }

    @Test
    void aBaselineIdWithoutABaselineDirectoryIsMisconfigured() {
        EvalSuite suite = new EvalSuite(new DatasetLoader(datasets, DatasetLimits.defaults()), new EvalRunner(), null, null,
                StoreLimits.defaults(), null);

        assertThatThrownBy(() -> suite.evaluate("sample.yaml", good(), List.of(m()), request("r").withBaselineRunId("base")))
                .isInstanceOf(BaseException.class)
                .extracting(e -> ((BaseException) e).getErrorMessage()).isEqualTo(ErrorMessages.EVAL_E028);
    }

    @Test
    void aSuiteWithoutStoreOrWriterStillRuns() {
        EvalSuite suite = new EvalSuite(new DatasetLoader(datasets, DatasetLimits.defaults()), new EvalRunner(), null, null,
                StoreLimits.defaults(), null);

        EvalOutcome outcome = suite.evaluate("sample.yaml", good(), List.of(m()), request("r"));

        assertThat(outcome.passesGate()).isTrue();
        assertThat(outcome.reportFiles()).isEmpty();
    }

    @Test
    void minPassRateFailsAndPasses() throws IOException {
        EvalTarget half = target(c -> answer(c.id().equals("a") ? "bad" : "good " + c.id()));
        Thresholds strict = Thresholds.defaults().withMinPassRate("m", 0.9);
        Thresholds loose = Thresholds.defaults().withMinPassRate("m", 0.6);

        EvalOutcome failing = suite().evaluate("sample.yaml", half, List.of(m()), request("t1").withThresholds(strict));
        EvalOutcome passing = suite().evaluate("sample.yaml", half, List.of(m()), request("t2").withThresholds(loose));

        assertThat(failing.violations()).containsExactly("pass rate of metric m is 0.6667, minimum is 0.9000");
        assertThat(failing.passesGate()).isFalse();
        assertThat(failing.consoleReport()).contains("GATE: FAILED", "pass rate of metric m is 0.6667");
        assertThat(passing.passesGate()).isTrue();
    }

    @Test
    void minMeanScoreFailsAndPasses() {
        EvalTarget half = target(c -> answer(c.id().equals("a") ? "bad" : "good " + c.id()));

        EvalOutcome failing = suite().evaluate("sample.yaml", half, List.of(m()),
                request("t1").withThresholds(Thresholds.defaults().withMinMeanScore("m", 0.9)));
        EvalOutcome passing = suite().evaluate("sample.yaml", half, List.of(m()),
                request("t2").withThresholds(Thresholds.defaults().withMinMeanScore("m", 0.6)));

        assertThat(failing.violations()).containsExactly("mean score of metric m is 0.6667, minimum is 0.9000");
        assertThat(passing.passesGate()).isTrue();
    }

    @Test
    void aThresholdOnAnAllSkippedOrAbsentMetricIsAViolation() {
        Metric skipped = metric("only_skipped", o -> MetricResult.skipped("only_skipped", "n/a"));
        Thresholds thresholds = Thresholds.defaults().withMinPassRate("only_skipped", 0.5)
                .withMinMeanScore("only_skipped", 0.5).withMinPassRate("absent", 0.1);

        EvalOutcome outcome = suite().evaluate("sample.yaml", good(), List.of(m(), skipped),
                request("t").withThresholds(thresholds));

        assertThat(outcome.violations()).containsExactlyInAnyOrder(
                "pass rate of metric absent is unavailable, minimum is 0.1000",
                "pass rate of metric only_skipped is unavailable, minimum is 0.5000",
                "mean score of metric only_skipped is unavailable, minimum is 0.5000");
        assertThat(outcome.passesGate()).isFalse();
    }

    @Test
    void maxIncompleteCasesCountsFailedTargets() {
        EvalTarget flaky = target(c -> {
            if (c.id().equals("a")) {
                throw new IllegalStateException("boom");
            }
            return answer("good " + c.id());
        });

        EvalOutcome strict = suite().evaluate("sample.yaml", flaky, List.of(m()), request("t1"));
        EvalOutcome loose = suite().evaluate("sample.yaml", flaky, List.of(m()),
                request("t2").withThresholds(Thresholds.defaults().withMaxIncompleteCases(1)));

        assertThat(strict.violations()).containsExactly("incomplete cases: 1 (allowed 0)");
        assertThat(strict.passesGate()).isFalse();
        assertThat(loose.passesGate()).isTrue();
    }

    @Test
    void maxMetricErrorsCountsErrorResults() {
        Metric broken = metric("broken", o -> MetricResult.error("broken", "judge call budget exhausted"));

        EvalOutcome strict = suite().evaluate("sample.yaml", good(), List.of(m(), broken), request("t1"));
        EvalOutcome loose = suite().evaluate("sample.yaml", good(), List.of(m(), broken),
                request("t2").withThresholds(Thresholds.defaults().withMaxMetricErrors(3)));

        assertThat(strict.violations()).containsExactly("metric errors: 3 (allowed 0)");
        assertThat(loose.passesGate()).isTrue();
    }

    @Test
    void aRunIdCollisionIsAnErrorWithAHint() {
        suite().evaluate("sample.yaml", good(), List.of(m()), request("same"));

        assertThatThrownBy(() -> suite().evaluate("sample.yaml", good(), List.of(m()), request("same")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("same").hasMessageContaining("unique run id")
                .extracting(e -> ((BaseException) e).getErrorMessage()).isEqualTo(ErrorMessages.EVAL_E018);
    }

    @Test
    void aHundredCharacterRunIdStillGivesValidReportFileNames() throws IOException {
        String longId = "r".repeat(100);
        makeBaseline("base");

        EvalOutcome outcome = suite().evaluate("sample.yaml", good(), List.of(m()),
                request(longId).withBaselineRunId("base"));

        assertThat(outcome.reportFiles()).hasSize(2).allSatisfy(name -> {
            assertThat(name.length()).isLessThanOrEqualTo(100);
            assertThat(name).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}").startsWith("eval-report-");
        });
        assertThat(names(reportDir)).containsExactlyInAnyOrderElementsOf(outcome.reportFiles());
        assertThat(names(storeDir)).contains("run-" + longId + ".json");
    }

    @Test
    void saveRunFalseAndWriteReportsFalseWriteNothing() throws IOException {
        EvalOutcome outcome = suite().evaluate("sample.yaml", good(), List.of(m()),
                request("quiet").withSaveRun(false).withWriteReports(false));

        assertThat(outcome.reportFiles()).isEmpty();
        assertThat(outcome.consoleReport()).isNotEmpty();
        assertThat(names(storeDir)).isEmpty();
        assertThat(names(reportDir)).isEmpty();
    }

    @Test
    void theMarkerReachesNoFileReportViolationOrString() throws IOException {
        writeDataset(MARKER);
        EvalTarget leaky = target(c -> new TargetOutput(MARKER + " answer " + c.id(), true,
                List.of(new RetrievedChunk(MARKER + "-id", MARKER + "-doc", MARKER + ".md", 1, MARKER + " chunk", 0.5)),
                List.of(MARKER + ".md"), Duration.ZERO, Map.of("note", MARKER)));
        Metric leakyMetric = metric("m", o -> MetricResult.passed("m", 1.0, "ok"));
        suite().evaluate("sample.yaml", leaky, List.of(leakyMetric), request("base"));
        Files.createDirectory(baselineDir);
        Files.copy(storeDir.resolve("run-base.json"), baselineDir.resolve("run-base.json"));
        EvalTarget failing = target(c -> new TargetOutput(MARKER + " bad", true, List.of(), List.of(), Duration.ZERO, Map.of()));
        Metric failingMetric = metric("m", o -> MetricResult.failed("m", 0.0, "no"));

        EvalOutcome outcome = suite().evaluate("sample.yaml", failing, List.of(failingMetric),
                request("cand").withBaselineRunId("base")
                        .withThresholds(Thresholds.defaults().withMinPassRate("m", 1.0)));

        assertThat(outcome.passesGate()).isFalse();
        assertThat(outcome.violations()).isNotEmpty().noneMatch(v -> v.contains(MARKER));
        assertThat(outcome.consoleReport()).doesNotContain(MARKER);
        assertThat(outcome.markdownReport()).doesNotContain(MARKER);
        assertThat(outcome.toString()).doesNotContain(MARKER).contains("cand", "FAILED");
        assertThat(outcome.run().toString()).doesNotContain(MARKER);
        assertThat(outcome.stored().toString()).doesNotContain(MARKER);
        assertThat(outcome.summary().toString()).doesNotContain(MARKER);
        assertThat(String.valueOf(outcome.comparison())).doesNotContain(MARKER);
        for (Path directory : List.of(storeDir, reportDir)) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    assertThat(Files.readString(file)).as(file.getFileName().toString()).doesNotContain(MARKER);
                }
            }
        }
    }

    @Test
    void outcomeAndRequestStringsPrintOnlyIdsAndCounts() {
        EvalRequest request = request("r1").withBaselineRunId("b1")
                .withRunConfig(RunConfig.defaults().withRunId("r1").withLabels(Map.of("note", MARKER)));

        EvalOutcome outcome = suite().evaluate("sample.yaml", good(), List.of(m()), request.withBaselineRunId(null));

        assertThat(request.toString()).doesNotContain(MARKER).contains("r1", "b1");
        assertThat(outcome.toString()).isEqualTo("EvalOutcome[runId=r1, cases=3, violations=0, gate=PASSED]");
    }

    @Test
    void thresholdsAndRequestValidateTheirInput() {
        assertThatThrownBy(() -> Thresholds.defaults().withMinPassRate("m", 1.1)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> Thresholds.defaults().withMinMeanScore("m", -0.1)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> Thresholds.defaults().withMinPassRate(" ", 0.5)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> Thresholds.defaults().withMaxIncompleteCases(-1)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> Thresholds.defaults().withMaxMetricErrors(-1)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> EvalRequest.defaults().withBaselineRunId("../x")).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> EvalRequest.defaults().withThresholds(null)).isInstanceOf(BaseException.class);
        assertThat(Thresholds.defaults().maxIncompleteCases()).isZero();
        assertThat(Thresholds.defaults().maxMetricErrors()).isZero();
        EvalRequest defaults = EvalRequest.defaults();
        assertThat(defaults.saveRun() && defaults.writeReports()).isTrue();
        assertThat(defaults.baselineRunId()).isNull();
    }
}
