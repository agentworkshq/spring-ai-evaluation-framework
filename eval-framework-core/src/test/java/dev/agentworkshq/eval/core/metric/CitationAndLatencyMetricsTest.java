package dev.agentworkshq.eval.core.metric;

import static dev.agentworkshq.eval.core.metric.MetricFixtures.chunk;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.chunkWithDocument;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.cited;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.noAnswerCase;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.withSources;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.MetricStatus;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CitationAndLatencyMetricsTest {

    @Test
    void citationScoresFractionOfDistinctExpectedSources() {
        CitationMetric metric = new CitationMetric(0.5);

        MetricResult result = metric.evaluate(withSources("a", "b", "c"), cited(List.of(), "a", "a", "c", "other"));

        assertThat(metric.name()).isEqualTo("citation");
        assertThat(result.score()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.reason()).isEqualTo("2 of 3 expected sources cited");
    }

    @Test
    void citationDefaultThresholdIsOne() {
        CitationMetric metric = new CitationMetric();

        assertThat(metric.evaluate(withSources("a", "b"), cited(List.of(), "a")).status()).isEqualTo(MetricStatus.FAILED);
        assertThat(metric.evaluate(withSources("a", "b"), cited(List.of(), "b", "a")).status()).isEqualTo(MetricStatus.PASSED);
    }

    @Test
    void citationNormalizesBothSides() {
        CitationMetric metric = new CitationMetric();

        assertThat(metric.evaluate(withSources("docs/a.pdf"), cited(List.of(), "./docs\\a.pdf")).status())
                .isEqualTo(MetricStatus.PASSED);
        assertThat(metric.evaluate(withSources(".\\docs\\a.pdf"), cited(List.of(), "docs/a.pdf")).status())
                .isEqualTo(MetricStatus.PASSED);
        assertThat(metric.evaluate(withSources("a.pdf"), cited(List.of(), "A.pdf")).status()).isEqualTo(MetricStatus.FAILED);
    }

    @Test
    void citationSkipsWithoutSourcesOrWhenNoAnswerExpected() {
        CitationMetric metric = new CitationMetric();

        assertThat(metric.evaluate(withSources(), cited(List.of(), "a")).status()).isEqualTo(MetricStatus.SKIPPED);
        assertThat(metric.evaluate(noAnswerCase(), cited(List.of(), "a")).status()).isEqualTo(MetricStatus.SKIPPED);
    }

    @Test
    void citationFailsWithZeroWhenNothingCited() {
        MetricResult result = new CitationMetric().evaluate(withSources("a"), cited(List.of()));

        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void citationThresholdValidation() {
        assertThatThrownBy(() -> new CitationMetric(-0.1)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CitationMetric(1.5)).isInstanceOf(ValidationException.class);
    }

    @Test
    void groundingPassesWhenEveryCitationMatchesAChunk() {
        CitationGroundingMetric metric = new CitationGroundingMetric();

        MetricResult result = metric.evaluate(withSources(), cited(List.of(chunk("a.pdf"), chunkWithDocument("doc-2")),
                "./a.pdf", "doc-2"));

        assertThat(metric.name()).isEqualTo("citation_grounded");
        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.reason()).isEqualTo("2 of 2 citations grounded in retrieved chunks");
    }

    @Test
    void groundingCatchesMadeUpCitations() {
        MetricResult result = new CitationGroundingMetric()
                .evaluate(withSources(), cited(List.of(chunk("a.pdf")), "a.pdf", "invented.pdf", "a.pdf", "also-invented"));

        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(result.score()).isEqualTo(0.5);
        assertThat(result.reason()).isEqualTo("2 of 4 citations grounded in retrieved chunks, ungrounded citations #2, #4");
    }

    @Test
    void groundingFailsWhenNothingWasRetrieved() {
        MetricResult result = new CitationGroundingMetric().evaluate(withSources(), cited(List.of(), "a"));

        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.reason()).endsWith("ungrounded citation #1");
    }

    @Test
    void groundingSkipsWhenNothingCited() {
        assertThat(new CitationGroundingMetric().evaluate(withSources(), cited(List.of(chunk("a")))).status())
                .isEqualTo(MetricStatus.SKIPPED);
    }

    @Test
    void latencyPassesAtTheLimitAndFailsAbove() {
        LatencyMetric metric = new LatencyMetric(Duration.ofMillis(100));

        MetricResult atLimit = metric.evaluate(withSources(), withLatency(Duration.ofMillis(100)));
        MetricResult above = metric.evaluate(withSources(), withLatency(Duration.ofMillis(101)));
        MetricResult zero = metric.evaluate(withSources(), withLatency(Duration.ZERO));

        assertThat(metric.name()).isEqualTo("latency");
        assertThat(atLimit.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(atLimit.score()).isEqualTo(1.0);
        assertThat(zero.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(above.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(above.score()).isEqualTo(0.0);
    }

    @Test
    void latencyRequiresPositiveMax() {
        assertThatThrownBy(() -> new LatencyMetric(null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new LatencyMetric(Duration.ZERO)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new LatencyMetric(Duration.ofMillis(-1))).isInstanceOf(ValidationException.class);
    }

    private static TargetOutput withLatency(Duration latency) {
        return new TargetOutput("a", true, List.of(), List.of(), latency, Map.of());
    }
}
