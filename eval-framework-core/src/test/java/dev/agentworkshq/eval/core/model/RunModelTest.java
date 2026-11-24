package dev.agentworkshq.eval.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.ValidationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunModelTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static TargetOutput output() {
        return new TargetOutput("answer", true, List.of(), List.of(), Duration.ofMillis(5), Map.of());
    }

    private static CaseResult completed(String id) {
        return new CaseResult(id, CaseStatus.COMPLETED, output(), null, List.of(), Duration.ZERO);
    }

    private static EvalRun run(Map<String, String> config, List<CaseResult> results) {
        return new EvalRun("run-1", "d", "fp", "target", T0, T0.plusSeconds(1), config, results);
    }

    @Test
    void retrievedChunkRules() {
        RetrievedChunk ok = new RetrievedChunk("c1", null, null, null, "", null);
        assertThat(ok.text()).isEmpty();
        assertThat(new RetrievedChunk("c1", "doc", "f.pdf", 0, "t", 0.5).page()).isZero();

        assertThatThrownBy(() -> new RetrievedChunk(" ", null, null, null, "t", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RetrievedChunk("c", null, null, null, null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RetrievedChunk("c", null, null, -1, "t", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RetrievedChunk("c", null, null, null, "t", Double.NaN)).isInstanceOf(ValidationException.class);
    }

    @Test
    void targetOutputRules() {
        assertThat(new TargetOutput("", false, null, null, Duration.ZERO, null).answer()).isEmpty();

        assertThatThrownBy(() -> new TargetOutput(null, true, null, null, Duration.ZERO, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new TargetOutput("a", true, null, null, Duration.ofMillis(-1), null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new TargetOutput("a", true, null, null, null, null)).isInstanceOf(ValidationException.class);
        List<RetrievedChunk> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new TargetOutput("a", true, withNull, null, Duration.ZERO, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void targetOutputCopiesAndSorts() {
        List<RetrievedChunk> chunks = new ArrayList<>(List.of(new RetrievedChunk("c", null, null, null, "t", null)));
        List<String> cited = new ArrayList<>(List.of("s"));
        Map<String, String> attributes = new HashMap<>(Map.of("b", "1", "a", "2"));

        TargetOutput out = new TargetOutput("a", true, chunks, cited, Duration.ZERO, attributes);
        chunks.clear();
        cited.clear();
        attributes.clear();

        assertThat(out.retrieved()).hasSize(1);
        assertThat(out.citedSources()).containsExactly("s");
        assertThat(out.attributes().keySet()).containsExactly("a", "b");
        assertThatThrownBy(() -> out.retrieved().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> out.citedSources().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> out.attributes().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void metricResultInvariants() {
        assertThat(MetricResult.passed("m", 1.0, "ok").status()).isEqualTo(MetricStatus.PASSED);
        assertThat(MetricResult.failed("m", 0.0, "no").status()).isEqualTo(MetricStatus.FAILED);
        assertThat(MetricResult.error("m", "bad").score()).isNull();
        assertThat(MetricResult.skipped("m", null).status()).isEqualTo(MetricStatus.SKIPPED);

        assertThatThrownBy(() -> MetricResult.passed("m", 1.1, "x")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> MetricResult.failed("m", -0.1, "x")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> MetricResult.passed("m", Double.NaN, "x")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new MetricResult("m", MetricStatus.PASSED, null, "x")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new MetricResult("m", MetricStatus.ERROR, 0.5, "x")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new MetricResult("m", MetricStatus.SKIPPED, 0.0, "x")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new MetricResult(" ", MetricStatus.ERROR, null, "x")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new MetricResult("m", null, null, "x")).isInstanceOf(ValidationException.class);
    }

    @Test
    void metricResultTruncatesLongReason() {
        String exact = "x".repeat(500);
        String truncated = MetricResult.error("m", "x".repeat(501)).reason();

        assertThat(MetricResult.error("m", exact).reason()).isEqualTo(exact);
        assertThat(truncated).hasSize(500).endsWith("...");
    }

    @Test
    void caseResultRules() {
        assertThat(completed("a").status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(new CaseResult("a", CaseStatus.SKIPPED_BUDGET, null, null, null, Duration.ZERO).output()).isNull();
        assertThat(new CaseResult("a", CaseStatus.TIMED_OUT, null, "timed out", null, Duration.ZERO).errorMessage())
                .isEqualTo("timed out");

        assertThatThrownBy(() -> new CaseResult("a", CaseStatus.COMPLETED, null, null, null, Duration.ZERO))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CaseResult("a", CaseStatus.TARGET_FAILED, null, null, null, Duration.ZERO))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CaseResult("a", CaseStatus.TIMED_OUT, null, " ", null, Duration.ZERO))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CaseResult("a", CaseStatus.TARGET_FAILED, null, "x".repeat(501), null, Duration.ZERO))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CaseResult("bad id", CaseStatus.SKIPPED_BUDGET, null, null, null, Duration.ZERO))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CaseResult("a", CaseStatus.SKIPPED_BUDGET, null, null, null, Duration.ofSeconds(-1)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CaseResult("a", null, null, null, null, Duration.ZERO)).isInstanceOf(ValidationException.class);
    }

    @Test
    void caseResultSortsAndRejectsDuplicateMetrics() {
        List<MetricResult> metrics = new ArrayList<>(List.of(MetricResult.skipped("b", null), MetricResult.skipped("a", null)));

        CaseResult result = new CaseResult("a", CaseStatus.COMPLETED, output(), null, metrics, Duration.ZERO);
        metrics.clear();

        assertThat(result.metricResults()).extracting(MetricResult::metricName).containsExactly("a", "b");
        assertThatThrownBy(() -> result.metricResults().add(MetricResult.skipped("c", null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new CaseResult("a", CaseStatus.COMPLETED, output(), null,
                List.of(MetricResult.skipped("m", null), MetricResult.error("m", "x")), Duration.ZERO))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void evalRunSortsAndCopies() {
        Map<String, String> config = new HashMap<>(Map.of("model", "m", "timeout", "5"));
        List<CaseResult> results = new ArrayList<>(List.of(completed("b"), completed("a")));

        EvalRun run = run(config, results);
        config.clear();
        results.clear();

        assertThat(run.config().keySet()).containsExactly("model", "timeout");
        assertThat(run.caseResults()).extracting(CaseResult::caseId).containsExactly("a", "b");
        assertThatThrownBy(() -> run.config().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> run.caseResults().add(completed("c"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void evalRunRules() {
        assertThatThrownBy(() -> run(Map.of(), List.of(completed("a"), completed("a")))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalRun("bad id", "d", "fp", "t", T0, T0, Map.of(), List.of()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalRun("r", "bad name", "fp", "t", T0, T0, Map.of(), List.of()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalRun("r", "d", " ", "t", T0, T0, Map.of(), List.of()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalRun("r", "d", "fp", " ", T0, T0, Map.of(), List.of()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalRun("r", "d", "fp", "t", T0, T0.minusSeconds(1), Map.of(), List.of()))
                .isInstanceOf(ValidationException.class);
        assertThat(new EvalRun("r", "d", "fp", "t", T0, T0, Map.of(), List.of()).finishedAt()).isEqualTo(T0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"apiKey", "API_KEY", "client-secret", "Token", "db.PASSWORD", "AUTHORIZATION", "monkey"})
    void evalRunRejectsSecretLikeKeysWithoutEchoingValue(String key) {
        assertThatThrownBy(() -> run(Map.of(key, "VALUE-MUST-NOT-LEAK"), List.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(key)
                .hasMessageNotContaining("VALUE-MUST-NOT-LEAK");
    }
}
