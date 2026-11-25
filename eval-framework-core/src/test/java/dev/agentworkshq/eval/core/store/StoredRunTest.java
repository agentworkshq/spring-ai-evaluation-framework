package dev.agentworkshq.eval.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.CaseResult;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StoredRunTest {

    private static final Instant T0 = StoreFixtures.T0;

    @Test
    void fromCopiesOnlyTheSafeFields() {
        TargetOutput output = new TargetOutput("hello", true,
                List.of(new RetrievedChunk("c1", null, null, null, "chunk text", null),
                        new RetrievedChunk("c2", null, null, null, "more", null)),
                List.of("s"), Duration.ofMillis(42), Map.of());
        CaseResult done = new CaseResult("a", CaseStatus.COMPLETED, output, null,
                List.of(MetricResult.passed("m", 1.0, "ok")), Duration.ofMillis(100));
        CaseResult failed = new CaseResult("b", CaseStatus.TARGET_FAILED, null, "IllegalStateException after 1 attempts",
                List.of(), Duration.ofMillis(5));
        CaseResult skipped = new CaseResult("c", CaseStatus.SKIPPED_BUDGET, null, null, List.of(), Duration.ZERO);
        EvalRun run = new EvalRun("r1", "ds", "fp", "target", T0, T0.plusSeconds(2), Map.of("k", "v"),
                List.of(skipped, failed, done));

        StoredRun stored = StoredRun.from(run);

        assertThat(stored.runId()).isEqualTo("r1");
        assertThat(stored.datasetName()).isEqualTo("ds");
        assertThat(stored.datasetFingerprint()).isEqualTo("fp");
        assertThat(stored.targetName()).isEqualTo("target");
        assertThat(stored.startedAt()).isEqualTo(T0);
        assertThat(stored.finishedAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(stored.config()).containsExactly(Map.entry("k", "v"));
        assertThat(stored.cases()).extracting(StoredCase::caseId).containsExactly("a", "b", "c");

        StoredCase a = stored.cases().get(0);
        assertThat(a.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(a.durationMs()).isEqualTo(100);
        assertThat(a.answered()).isTrue();
        assertThat(a.answerChars()).isEqualTo(5);
        assertThat(a.latencyMs()).isEqualTo(42);
        assertThat(a.retrievedCount()).isEqualTo(2);
        assertThat(a.metricResults()).containsExactly(MetricResult.passed("m", 1.0, "ok"));

        StoredCase b = stored.cases().get(1);
        assertThat(b.errorMessage()).isEqualTo("IllegalStateException after 1 attempts");
        assertThat(b.answered()).isNull();
        assertThat(b.answerChars()).isNull();
        assertThat(b.latencyMs()).isNull();
        assertThat(b.retrievedCount()).isNull();
    }

    @Test
    void storedCaseRules() {
        assertThatThrownBy(() -> new StoredCase("a b", CaseStatus.COMPLETED, null, 0, null, null, null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new StoredCase("a", null, null, 0, null, null, null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new StoredCase("a", CaseStatus.COMPLETED, "x".repeat(501), 0, null, null, null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new StoredCase("a", CaseStatus.TIMED_OUT, null, 0, null, null, null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new StoredCase("a", CaseStatus.COMPLETED, null, -1, null, null, null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new StoredCase("a", CaseStatus.COMPLETED, null, 0, null, -1, null, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new StoredCase("a", CaseStatus.COMPLETED, null, 0, null, null, -1L, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new StoredCase("a", CaseStatus.COMPLETED, null, 0, null, null, null, -1, null))
                .isInstanceOf(ValidationException.class);
        List<MetricResult> duplicate = List.of(MetricResult.passed("m", 1, ""), MetricResult.failed("m", 0, ""));
        assertThatThrownBy(() -> new StoredCase("a", CaseStatus.COMPLETED, null, 0, null, null, null, null, duplicate))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void storedCaseSortsAndCopiesMetricResults() {
        List<MetricResult> source = new ArrayList<>(List.of(MetricResult.passed("b", 1, ""), MetricResult.passed("a", 1, "")));

        StoredCase stored = new StoredCase("a", CaseStatus.COMPLETED, null, 0, null, null, null, null, source);
        source.clear();

        assertThat(stored.metricResults()).extracting(MetricResult::metricName).containsExactly("a", "b");
        assertThatThrownBy(() -> stored.metricResults().add(MetricResult.passed("c", 1, "")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void storedRunRules() {
        assertThatThrownBy(() -> new StoredRun("../x", "ds", "fp", "t", T0, T0, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new StoredRun("r", "ds", "fp", "t", T0, T0.minusSeconds(1), null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new StoredRun("r", "ds", " ", "t", T0, T0, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new StoredRun("r", "ds", "fp", "t", T0, T0, Map.of("k", "x".repeat(201)), null))
                .isInstanceOf(ValidationException.class);
        StoredCase one = StoreFixtures.completed("a");
        assertThatThrownBy(() -> new StoredRun("r", "ds", "fp", "t", T0, T0, null, List.of(one, one)))
                .isInstanceOf(ValidationException.class);
        assertThat(new StoredRun("r", "ds", "fp", "t", T0, T0, Map.of("k", "x".repeat(200)), null).cases()).isEmpty();
    }

    @Test
    void secretLookingConfigKeysAreRejected() {
        assertThatThrownBy(() -> new StoredRun("r", "ds", "fp", "t", T0, T0, Map.of("api-key", "v"), null))
                .isInstanceOf(BaseException.class)
                .extracting(e -> ((BaseException) e).getErrorMessage()).isEqualTo(ErrorMessages.EVAL_E011);
        assertThat(new StoredRun("r", "ds", "fp", "t", T0, T0, Map.of("max-tokens", "10"), null).config()).hasSize(1);
    }

    @Test
    void configAndCasesAreSortedAndCopied() {
        Map<String, String> config = new HashMap<>(Map.of("b", "1", "a", "2"));
        List<StoredCase> cases = new ArrayList<>(List.of(StoreFixtures.completed("z"), StoreFixtures.completed("y")));

        StoredRun run = new StoredRun("r", "ds", "fp", "t", T0, T0, config, cases);
        config.clear();
        cases.clear();

        assertThat(run.config().keySet()).containsExactly("a", "b");
        assertThat(run.cases()).extracting(StoredCase::caseId).containsExactly("y", "z");
    }

    @Test
    void storedRunHoldsNoQuestionAnswerOrChunkText() {
        EvalRun run = StoreFixtures.evalRunWithMarker("UNIQUE-MARKER-TEXT");

        assertThat(StoredRun.from(run).toString()).doesNotContain("UNIQUE-MARKER-TEXT");
    }
}
