package dev.agentworkshq.eval.core.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.ValidationException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class RunConfigTest {

    private static void rejects(Supplier<RunConfig> supplier) {
        assertThatThrownBy(supplier::get).isInstanceOf(ValidationException.class)
                .extracting(e -> ((ValidationException) e).getErrorCode()).isEqualTo("EVAL_E011");
    }

    @Test
    void timeoutsStartAtOneMillisecond() {
        List<Function<Duration, RunConfig>> setters = List.of(RunConfig.defaults()::withPerCaseTimeout,
                RunConfig.defaults()::withPerMetricTimeout, RunConfig.defaults()::withTotalTimeout);

        for (Function<Duration, RunConfig> setter : setters) {
            for (Duration bad : List.of(Duration.ZERO, Duration.ofNanos(999_999), Duration.ofDays(365).plusNanos(1),
                    Duration.ofMillis(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE))) {
                rejects(() -> setter.apply(bad));
            }
            assertThat(setter.apply(Duration.ofMillis(1))).isNotNull();
            assertThat(setter.apply(Duration.ofDays(365))).isNotNull();
        }
    }

    @Test
    void defaultsMatchTheDocumentedValues() {
        RunConfig c = RunConfig.defaults();

        assertThat(c.runId()).isNull();
        assertThat(c.maxCases()).isEqualTo(1000);
        assertThat(c.includeTags()).isEmpty();
        assertThat(c.concurrency()).isEqualTo(1);
        assertThat(c.perCaseTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(c.perMetricTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(c.totalTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(c.maxAttempts()).isEqualTo(1);
        assertThat(c.retryDelay()).isEqualTo(Duration.ZERO);
        assertThat(c.effectiveMaxAbandonedCalls()).isEqualTo(1);
        assertThat(c.maxJudgeCalls()).isEqualTo(500);
        assertThat(c.labels()).isEmpty();
    }

    @Test
    void abandonedLimitFollowsConcurrencyUnlessSet() {
        assertThat(RunConfig.defaults().withConcurrency(4).effectiveMaxAbandonedCalls()).isEqualTo(4);
        assertThat(RunConfig.defaults().withConcurrency(4).withMaxAbandonedCalls(2).effectiveMaxAbandonedCalls()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidRunId() {
        rejects(() -> RunConfig.defaults().withRunId("bad id"));
        rejects(() -> RunConfig.defaults().withRunId(""));
        assertThat(RunConfig.defaults().withRunId("run-1").runId()).isEqualTo("run-1");
    }

    @Test
    void rejectsOutOfRangeNumbers() {
        rejects(() -> RunConfig.defaults().withMaxCases(0));
        rejects(() -> RunConfig.defaults().withMaxCases(100_001));
        rejects(() -> RunConfig.defaults().withConcurrency(0));
        rejects(() -> RunConfig.defaults().withConcurrency(17));
        rejects(() -> RunConfig.defaults().withMaxAttempts(0));
        rejects(() -> RunConfig.defaults().withMaxAttempts(6));
        rejects(() -> RunConfig.defaults().withMaxAbandonedCalls(0));
        rejects(() -> RunConfig.defaults().withMaxJudgeCalls(-1));
        rejects(() -> RunConfig.defaults().withMaxJudgeCalls(100_001));
        assertThat(RunConfig.defaults().withMaxJudgeCalls(0).maxJudgeCalls()).isZero();
        assertThat(RunConfig.defaults().withMaxJudgeCalls(100_000).maxJudgeCalls()).isEqualTo(100_000);
        assertThat(RunConfig.defaults().withMaxCases(100_000).withConcurrency(16).withMaxAttempts(5).maxAttempts()).isEqualTo(5);
    }

    @Test
    void rejectsBadDurations() {
        rejects(() -> RunConfig.defaults().withPerCaseTimeout(Duration.ZERO));
        rejects(() -> RunConfig.defaults().withPerCaseTimeout(null));
        rejects(() -> RunConfig.defaults().withPerMetricTimeout(Duration.ofMillis(-1)));
        rejects(() -> RunConfig.defaults().withTotalTimeout(Duration.ZERO));
        rejects(() -> RunConfig.defaults().withTotalTimeout(Duration.ofDays(366)));
        rejects(() -> RunConfig.defaults().withRetryDelay(Duration.ofMillis(-1)));
        rejects(() -> RunConfig.defaults().withRetryDelay(null));
        assertThat(RunConfig.defaults().withRetryDelay(Duration.ZERO).retryDelay()).isEqualTo(Duration.ZERO);
    }

    @Test
    void rejectsBadTags() {
        rejects(() -> RunConfig.defaults().withIncludeTags(Set.of(" ")));
        rejects(() -> RunConfig.defaults().withIncludeTags(Set.of("x".repeat(51))));
    }

    @Test
    void withMethodsReturnNewInstancesAndKeepTheOriginal() {
        RunConfig base = RunConfig.defaults();

        RunConfig changed = base.withConcurrency(3).withMaxAttempts(2).withIncludeTags(Set.of("b", "a"));

        assertThat(changed).isNotSameAs(base);
        assertThat(base.concurrency()).isEqualTo(1);
        assertThat(changed.concurrency()).isEqualTo(3);
        assertThat(changed.includeTags()).containsExactly("a", "b");
        assertThat(base.withMaxCases(5).maxCases()).isEqualTo(5);
        assertThat(base.withPerCaseTimeout(Duration.ofSeconds(1)).perCaseTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(base.withPerMetricTimeout(Duration.ofSeconds(2)).perMetricTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(base.withTotalTimeout(Duration.ofSeconds(3)).totalTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(base.withRetryDelay(Duration.ofSeconds(4)).retryDelay()).isEqualTo(Duration.ofSeconds(4));
        assertThat(base.withMaxAbandonedCalls(7).maxAbandonedCalls()).isEqualTo(7);
        assertThat(base.withLabels(Map.of("a", "b")).labels()).containsEntry("a", "b");
        assertThat(base.withRunId("r").runId()).isEqualTo("r");
    }

    @Test
    void collectionsAreCopiedAndUnmodifiable() {
        Map<String, String> labels = new HashMap<>(Map.of("team", "x"));
        RunConfig c = RunConfig.defaults().withLabels(labels);
        labels.put("later", "y");

        assertThat(c.labels()).containsOnlyKeys("team");
        assertThatThrownBy(() -> c.labels().put("a", "b")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.includeTags().add("a")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void enforcesLabelLimits() {
        Map<String, String> many = new HashMap<>();
        for (int i = 0; i < 21; i++) {
            many.put("k" + i, "v");
        }
        Map<String, String> twenty = new HashMap<>(many);
        twenty.remove("k0");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("a", null);

        rejects(() -> RunConfig.defaults().withLabels(many));
        rejects(() -> RunConfig.defaults().withLabels(Map.of(" ", "v")));
        rejects(() -> RunConfig.defaults().withLabels(Map.of("k".repeat(201), "v")));
        rejects(() -> RunConfig.defaults().withLabels(Map.of("k", "v".repeat(201))));
        rejects(() -> RunConfig.defaults().withLabels(nullValue));
        rejects(() -> RunConfig.defaults().withLabels(Map.of("api-key", "v")));
        assertThat(RunConfig.defaults().withLabels(twenty).labels()).hasSize(20);
        assertThat(RunConfig.defaults().withLabels(Map.of("k".repeat(200), "v".repeat(200))).labels()).hasSize(1);
    }

    @Test
    void secretLabelErrorNeverEchoesTheValue() {
        assertThatThrownBy(() -> RunConfig.defaults().withLabels(Map.of("password", "SECRET-MARKER-TEXT")))
                .hasMessageNotContaining("SECRET-MARKER-TEXT");
    }
}
