package dev.agentworkshq.eval.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.agentworkshq.eval.core.compare.ComparisonConfig;
import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.EvalDataset;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.runner.EvalRunner;
import dev.agentworkshq.eval.core.runner.RunConfig;
import dev.agentworkshq.eval.core.store.FileRunStore;
import dev.agentworkshq.eval.core.store.StoreLimits;
import dev.agentworkshq.eval.core.store.StoredCase;
import dev.agentworkshq.eval.core.store.StoredRun;
import dev.agentworkshq.eval.core.suite.Thresholds;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

class MetricNameValidationTest {

    static Stream<String> badNames() {
        return Stream.of("bad name", "bad\nname", "bad\u001b[31mname", "bad/name", "n".repeat(101));
    }

    @ParameterizedTest
    @MethodSource("badNames")
    void metricResultsRejectTheName(String name) {
        assertRejected(name, "EVAL_E010", "metricName", () -> MetricResult.passed(name, 1.0, "ok"));
    }

    @ParameterizedTest
    @MethodSource("badNames")
    void theRunnerRejectsTheNameByPosition(String name) {
        EvalDataset dataset = new EvalDataset("d",
                List.of(new EvalCase("a", "q", null, null, false, null, null, null, null)));

        assertRejected(name, "EVAL_E012", "position 2",
                () -> new EvalRunner().run(dataset, target(), List.of(metric("good"), metric(name)), RunConfig.defaults()));
    }

    @ParameterizedTest
    @MethodSource("badNames")
    void thresholdsRejectTheName(String name) {
        assertRejected(name, "EVAL_E010", "minPassRate",
                () -> Thresholds.defaults().withMinPassRate(name, 0.5));
        assertRejected(name, "EVAL_E010", "minMeanScore",
                () -> new Thresholds(Map.of(), Map.of(name, 0.5), 0, 0));
    }

    @ParameterizedTest
    @MethodSource("badNames")
    void comparisonConfigRejectsTheName(String name) {
        assertRejected(name, "EVAL_E010", "metricTolerances",
                () -> ComparisonConfig.defaults().withMetricTolerance(name, 0.5));
        assertRejected(name, "EVAL_E010", "minPassRates",
                () -> ComparisonConfig.defaults().withMinPassRate(name, 0.5));
    }

    @ParameterizedTest
    @MethodSource("badNames")
    void aHandEditedRunFileIsRejected(String name, @TempDir Path dir) throws IOException {
        FileRunStore store = new FileRunStore(dir, StoreLimits.defaults());
        StoredCase stored = new StoredCase("a", CaseStatus.COMPLETED, null, 1, true, 1, 1L, 0,
                List.of(MetricResult.passed("original", 1.0, "ok")));
        store.save(new StoredRun("r1", "ds", "fp", "t", Instant.EPOCH, Instant.EPOCH, Map.of(), List.of(stored)));
        Path file = dir.resolve("run-r1.json");
        String json = Files.readString(file);
        assertThat(json).contains("\"original\"");
        Files.writeString(file, json.replace("\"original\"", JsonMapper.shared().writeValueAsString(name)));

        assertRejected(name, "EVAL_E019", "run-r1.json", () -> store.load("r1"));
    }

    @Test
    void aNameOfOneHundredCharactersIsAccepted() {
        String name = "m._-" + "n".repeat(96);

        assertThat(MetricResult.passed(name, 1.0, "ok").metricName()).isEqualTo(name);
        assertThat(Thresholds.defaults().withMinPassRate(name, 1.0).minPassRate()).containsKey(name);
        assertThat(ComparisonConfig.defaults().withMetricTolerance(name, 0.1).toleranceFor(name)).isEqualTo(0.1);
    }

    private static void assertRejected(String name, String code, String hint, ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);

        assertThat(thrown).isInstanceOf(BaseException.class).hasMessageContaining(hint);
        assertThat(((BaseException) thrown).getErrorCode()).isEqualTo(code);
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertThat(t.getMessage()).doesNotContain(name);
        }
    }

    private static Metric metric(String name) {
        return new Metric() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
                return MetricResult.skipped("good", "n/a");
            }
        };
    }

    private static EvalTarget target() {
        return new EvalTarget() {
            @Override
            public String name() {
                return "t";
            }

            @Override
            public TargetOutput run(EvalCase evalCase) {
                throw new AssertionError("the target must not be called");
            }
        };
    }
}
