package dev.agentworkshq.eval.junit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.dataset.DatasetLimits;
import dev.agentworkshq.eval.core.dataset.DatasetLoader;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.Text;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.runner.EvalRunner;
import dev.agentworkshq.eval.core.runner.RunConfig;
import dev.agentworkshq.eval.core.store.FileRunStore;
import dev.agentworkshq.eval.core.store.StoreLimits;
import dev.agentworkshq.eval.core.suite.EvalOutcome;
import dev.agentworkshq.eval.core.suite.EvalRequest;
import dev.agentworkshq.eval.core.suite.EvalSuite;
import dev.agentworkshq.eval.core.suite.Thresholds;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

class EvalAssertionsTest {

    private static final String MARKER = "SECRET-MARKER-TEXT";

    @TempDir
    Path dir;

    Path baselines;

    @BeforeEach
    void setUp() throws IOException {
        Path datasets = Files.createDirectory(dir.resolve("datasets"));
        Files.writeString(datasets.resolve("sample.yaml"), """
                name: sample
                cases:
                  - id: a
                    question: %s a
                  - id: b
                    question: %s b
                  - id: c
                    question: %s c
                """.formatted(MARKER, MARKER, MARKER));
        baselines = dir.resolve("baselines");
    }

    private EvalSuite suite(Path baselineDir) {
        return new EvalSuite(new DatasetLoader(dir.resolve("datasets"), DatasetLimits.defaults()), new EvalRunner(),
                new FileRunStore(dir.resolve("runs"), StoreLimits.defaults()), baselineDir, StoreLimits.defaults(), null);
    }

    private static EvalTarget target(Function<EvalCase, TargetOutput> body) {
        return new EvalTarget() {
            @Override
            public String name() {
                return "fake";
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
        return metric("m", o -> o.answer().contains("good") ? MetricResult.passed("m", 1.0, "ok")
                : MetricResult.failed("m", 0.0, "no"));
    }

    private static EvalRequest request(String runId) {
        return EvalRequest.defaults().withRunConfig(RunConfig.defaults().withRunId(runId)).withWriteReports(false);
    }

    private EvalOutcome good(String runId, String baselineId) throws IOException {
        if (baselineId != null && !Files.exists(baselines.resolve("run-" + baselineId + ".json"))) {
            suite(null).evaluate("sample.yaml", target(c -> answer(MARKER + " good " + c.id())), List.of(m()), request(baselineId));
            Files.createDirectories(baselines);
            Files.copy(dir.resolve("runs").resolve("run-" + baselineId + ".json"), baselines.resolve("run-" + baselineId + ".json"));
        }
        return suite(baselines).evaluate("sample.yaml", target(c -> answer(MARKER + " good " + c.id())), List.of(m()),
                baselineId == null ? request(runId) : request(runId).withBaselineRunId(baselineId));
    }

    private EvalOutcome bad(String runId) throws IOException {
        good("unused", "base");
        EvalTarget flaky = target(c -> {
            if (c.id().equals("c")) {
                throw new IllegalStateException(MARKER);
            }
            return answer(MARKER + (c.id().equals("b") ? " bad" : " good"));
        });
        Metric broken = metric("e", o -> MetricResult.error("e", "judge call budget exhausted"));
        return suite(baselines).evaluate("sample.yaml", flaky, List.of(m(), broken),
                request(runId).withBaselineRunId("base").withThresholds(Thresholds.defaults().withMinPassRate("m", 1.0)));
    }

    private static void assertSafeFailure(Runnable check, String... expectedParts) {
        assertThatThrownBy(check::run).isInstanceOf(AssertionFailedError.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(MARKER).contains(expectedParts));
    }

    @Test
    void assertGatePassesWhenTheGatePasses() throws IOException {
        assertThatCode(() -> EvalAssertions.assertGate(good("pass", "base"))).doesNotThrowAnyException();
    }

    @Test
    void assertGateFailsWithTheConsoleReport() throws IOException {
        EvalOutcome outcome = bad("fail");

        assertThatThrownBy(() -> EvalAssertions.assertGate(outcome)).isInstanceOf(AssertionFailedError.class)
                .hasMessage(outcome.consoleReport())
                .satisfies(e -> assertThat(e.getMessage()).contains("GATE: FAILED").doesNotContain(MARKER));
    }

    @Test
    void theFailureMessageIsCapped() throws IOException {
        EvalOutcome real = bad("cap");
        EvalOutcome huge = new EvalOutcome(real.run(), real.stored(), real.summary(), real.comparison(),
                real.baselineRunId(), real.violations(), "x".repeat(50_000), real.markdownReport(), real.reportFiles());

        assertThatThrownBy(() -> EvalAssertions.assertGate(huge)).isInstanceOf(AssertionFailedError.class)
                .satisfies(e -> assertThat(e.getMessage()).hasSize(EvalAssertions.MAX_MESSAGE_CHARS)
                        .isEqualTo(Text.truncate(huge.consoleReport(), EvalAssertions.MAX_MESSAGE_CHARS)));
    }

    @Test
    void aShortReportIsNotCut() throws IOException {
        EvalOutcome real = bad("short");

        assertThatThrownBy(() -> EvalAssertions.assertGate(real)).hasMessage(real.consoleReport());
        assertThat(real.consoleReport().length()).isLessThan(EvalAssertions.MAX_MESSAGE_CHARS);
    }

    @Test
    void everyFluentCheckPassesOnAGoodOutcome() throws IOException {
        EvalOutcome outcome = good("fluent", "base");

        assertThatCode(() -> EvalAssertions.assertThat(outcome).passesGate().metricPassRateAtLeast("m", 1.0)
                .metricMeanScoreAtLeast("m", 1.0).noCaseRegressions().noMetricErrors().allCasesCompleted())
                .doesNotThrowAnyException();
    }

    @Test
    void everyFluentCheckFailsSafelyOnABadOutcome() throws IOException {
        EvalOutcome outcome = bad("bad");

        assertSafeFailure(() -> EvalAssertions.assertThat(outcome).passesGate(), "Run bad", "the gate failed with");
        assertSafeFailure(() -> EvalAssertions.assertThat(outcome).metricPassRateAtLeast("m", 0.9),
                "pass rate of metric m is 0.5000", "expected at least 0.9000");
        assertSafeFailure(() -> EvalAssertions.assertThat(outcome).metricMeanScoreAtLeast("m", 0.9),
                "mean score of metric m is 0.5000");
        assertSafeFailure(() -> EvalAssertions.assertThat(outcome).metricPassRateAtLeast("absent", 0.1),
                "metric absent has no pass rate");
        assertSafeFailure(() -> EvalAssertions.assertThat(outcome).metricMeanScoreAtLeast("e", 0.1),
                "metric e has no mean score");
        assertSafeFailure(() -> EvalAssertions.assertThat(outcome).noCaseRegressions(), "cases regressed", "b", "c");
        assertSafeFailure(() -> EvalAssertions.assertThat(outcome).noMetricErrors(), "metric errors in metrics e");
        assertSafeFailure(() -> EvalAssertions.assertThat(outcome).allCasesCompleted(), "1 cases did not complete");
    }

    @Test
    void noCaseRegressionsFailsWithoutABaseline() throws IOException {
        EvalOutcome outcome = good("solo", null);

        assertSafeFailure(() -> EvalAssertions.assertThat(outcome).noCaseRegressions(), "no baseline comparison");
        assertThatCode(() -> EvalAssertions.assertThat(outcome).passesGate().allCasesCompleted()).doesNotThrowAnyException();
    }

    @Test
    void aThresholdStyleCheckPassesBelowTheActualValue() throws IOException {
        EvalOutcome outcome = bad("below");

        assertThatCode(() -> EvalAssertions.assertThat(outcome).metricPassRateAtLeast("m", 0.5).metricMeanScoreAtLeast("m", 0.5))
                .doesNotThrowAnyException();
    }
}
