package dev.agentworkshq.eval.core.metric;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.NameRules;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricContractTest {

    private static final String MARKER = "SECRET-MARKER-TEXT";

    private static List<Metric> allMetrics() {
        return List.of(
                new HitAtKMetric(3),
                new RecallAtKMetric(3),
                new PrecisionAtKMetric(3, 0.5),
                new ReciprocalRankMetric(3),
                new AnswerContainsMetric(),
                new AnswerNotContainsMetric(),
                new RegexMetric("regex_must", "SECRET-MARKER-TEXT", RegexMetric.Mode.MUST_MATCH),
                new RegexMetric("regex_must_not", "SECRET-MARKER-TEXT", RegexMetric.Mode.MUST_NOT_MATCH),
                new NoAnswerMetric(),
                new CitationMetric(),
                new CitationGroundingMetric(),
                new LatencyMetric(Duration.ofSeconds(1)));
    }

    private static List<EvalCase> cases() {
        return List.of(
                new EvalCase("c1", MARKER, MARKER, List.of("a.pdf", MARKER + ".pdf"), false, null, null,
                        List.of(MARKER, "other"), List.of(MARKER + "x", "nothere")),
                new EvalCase("c2", MARKER, null, null, true, null, null, null, List.of(MARKER)),
                new EvalCase("c3", MARKER, null, List.of("a.pdf"), false, null, null, List.of(MARKER), null));
    }

    private static List<TargetOutput> outputs() {
        RetrievedChunk text = new RetrievedChunk("k1", "doc", MARKER + ".pdf", 1, MARKER, 0.9);
        RetrievedChunk bare = new RetrievedChunk("k2", null, null, null, "", null);
        return List.of(
                new TargetOutput(MARKER, true, List.of(text, bare), List.of(MARKER + ".pdf", "made-up"), Duration.ofMillis(5), Map.of()),
                new TargetOutput("", false, List.of(), List.of(), Duration.ZERO, Map.of()),
                new TargetOutput("", true, List.of(bare), List.of(), Duration.ofSeconds(60), Map.of()),
                new TargetOutput(MARKER + " " + MARKER, true, List.of(text), List.of(MARKER), Duration.ofSeconds(2), Map.of()),
                new TargetOutput("x".repeat(60_000), true, List.of(), List.of(" "), Duration.ofMillis(1), Map.of()));
    }

    @Test
    void namesAreValidForResultsAndHaveNoSpaces() {
        for (Metric metric : allMetrics()) {
            assertThat(NameRules.isValid(metric.name())).as(metric.name()).isTrue();
            assertThat(metric.name()).doesNotContain(" ");
        }
    }

    @Test
    void reasonsNeverContainDatasetOrAnswerText() {
        for (Metric metric : allMetrics()) {
            for (EvalCase evalCase : cases()) {
                for (TargetOutput output : outputs()) {
                    MetricResult result = metric.evaluate(evalCase, output);
                    if (result.reason() != null) {
                        assertThat(result.reason()).as(metric.name()).doesNotContain("SECRET").doesNotContain("made-up");
                    }
                }
            }
        }
    }

    @Test
    void sameInputTwiceGivesEqualResults() {
        for (Metric metric : allMetrics()) {
            for (EvalCase evalCase : cases()) {
                for (TargetOutput output : outputs()) {
                    assertThat(metric.evaluate(evalCase, output)).isEqualTo(metric.evaluate(evalCase, output));
                }
            }
        }
    }

    @Test
    void everyMetricReturnsAResultForEdgeCaseOutputs() {
        for (Metric metric : allMetrics()) {
            for (EvalCase evalCase : cases()) {
                for (TargetOutput output : outputs()) {
                    MetricResult result = metric.evaluate(evalCase, output);
                    assertThat(result).as(metric.name()).isNotNull();
                    assertThat(result.metricName()).isEqualTo(metric.name());
                    assertThat(result.status()).isNotNull();
                }
            }
        }
    }
}
