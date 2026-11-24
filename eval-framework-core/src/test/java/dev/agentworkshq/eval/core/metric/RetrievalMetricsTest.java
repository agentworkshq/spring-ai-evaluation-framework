package dev.agentworkshq.eval.core.metric;

import static dev.agentworkshq.eval.core.metric.MetricFixtures.anonymousChunk;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.chunk;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.chunkWithDocument;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.noAnswerCase;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.retrieved;
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

class RetrievalMetricsTest {

    @Test
    void metricNames() {
        assertThat(new HitAtKMetric(5).name()).isEqualTo("hit_at_5");
        assertThat(new RecallAtKMetric(3).name()).isEqualTo("recall_at_3");
        assertThat(new PrecisionAtKMetric(10, 0.5).name()).isEqualTo("precision_at_10");
        assertThat(new ReciprocalRankMetric(5).name()).isEqualTo("mrr_at_5");
    }

    @Test
    void constructorValidation() {
        for (int k : new int[] {0, -1, 101}) {
            assertThatThrownBy(() -> new HitAtKMetric(k)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> new RecallAtKMetric(k)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> new PrecisionAtKMetric(k, 0.5)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> new ReciprocalRankMetric(k)).isInstanceOf(ValidationException.class);
        }
        for (double threshold : new double[] {-0.1, 1.1, Double.NaN}) {
            assertThatThrownBy(() -> new RecallAtKMetric(1, threshold)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> new PrecisionAtKMetric(1, threshold)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> new ReciprocalRankMetric(1, threshold)).isInstanceOf(ValidationException.class);
        }
        assertThat(new HitAtKMetric(1).name()).isEqualTo("hit_at_1");
        assertThat(new HitAtKMetric(100).name()).isEqualTo("hit_at_100");
    }

    @Test
    void allSkipWhenNoExpectedSourcesOrNoAnswerExpected() {
        List<Metric> metrics = List.of(new HitAtKMetric(3), new RecallAtKMetric(3), new PrecisionAtKMetric(3, 0.5),
                new ReciprocalRankMetric(3));
        for (Metric metric : metrics) {
            MetricResult noSources = metric.evaluate(withSources(), retrieved(chunk("a")));
            MetricResult noAnswer = metric.evaluate(noAnswerCase(), retrieved(chunk("a")));
            assertThat(noSources.status()).isEqualTo(MetricStatus.SKIPPED);
            assertThat(noSources.reason()).isNotBlank();
            assertThat(noAnswer.status()).isEqualTo(MetricStatus.SKIPPED);
        }
    }

    @Test
    void allFailWithZeroWhenNothingRetrieved() {
        List<Metric> metrics = List.of(new HitAtKMetric(3), new RecallAtKMetric(3), new PrecisionAtKMetric(3, 0.0),
                new ReciprocalRankMetric(3, 0.0));
        for (Metric metric : metrics) {
            MetricResult result = metric.evaluate(withSources("a"), retrieved());
            assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
            assertThat(result.score()).isEqualTo(0.0);
        }
    }

    @Test
    void hitAtKPassesWithRelevantChunkExactlyAtRankK() {
        MetricResult result = new HitAtKMetric(3).evaluate(withSources("a"), retrieved(chunk("x"), chunk("y"), chunk("a")));

        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.reason()).isEqualTo("first relevant chunk at rank 3 of 3");
    }

    @Test
    void hitAtKFailsWithRelevantChunkAtRankKPlusOne() {
        MetricResult result = new HitAtKMetric(2).evaluate(withSources("a"), retrieved(chunk("x"), chunk("y"), chunk("a")));

        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.reason()).contains("rank 3");
    }

    @Test
    void kLargerThanRetrievedSizeUsesWhatWasRetrieved() {
        MetricResult hit = new HitAtKMetric(50).evaluate(withSources("a"), retrieved(chunk("a")));
        MetricResult miss = new HitAtKMetric(50).evaluate(withSources("a"), retrieved(chunk("x")));

        assertThat(hit.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(miss.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(miss.reason()).isEqualTo("no relevant chunk within the first 1 of 1");
    }

    @Test
    void recallCountsDistinctExpectedSourcesCovered() {
        TargetOutput output = retrieved(chunk("a"), chunk("a"), chunk("b"), chunk("c"));

        MetricResult result = new RecallAtKMetric(3, 0.5).evaluate(withSources("a", "b", "c"), output);

        assertThat(result.score()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.reason()).isEqualTo("2 of 3 expected sources covered in the first 3 chunks");
    }

    @Test
    void recallDefaultThresholdIsOneAndDuplicateExpectedSourcesCountOnce() {
        MetricResult partial = new RecallAtKMetric(3).evaluate(withSources("a", "b"), retrieved(chunk("a")));
        MetricResult full = new RecallAtKMetric(3).evaluate(withSources("a", "a", "./a"), retrieved(chunk("a")));

        assertThat(partial.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(partial.score()).isEqualTo(0.5);
        assertThat(full.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(full.score()).isEqualTo(1.0);
    }

    @Test
    void precisionCountsDuplicateChunksOfOneDocumentEach() {
        TargetOutput output = retrieved(chunk("a"), chunk("a"), chunk("x"), chunk("a"));

        MetricResult result = new PrecisionAtKMetric(4, 0.75).evaluate(withSources("a"), output);

        assertThat(result.score()).isEqualTo(0.75);
        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.reason()).isEqualTo("3 of 4 chunks relevant");
    }

    @Test
    void precisionDividesByRetrievedSizeWhenSmallerThanK() {
        MetricResult result = new PrecisionAtKMetric(10, 1.0).evaluate(withSources("a"), retrieved(chunk("a"), chunk("x")));

        assertThat(result.score()).isEqualTo(0.5);
        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
    }

    @Test
    void precisionFailsBelowThresholdAndIgnoresChunksBeyondK() {
        MetricResult result = new PrecisionAtKMetric(2, 0.6).evaluate(withSources("a"),
                retrieved(chunk("a"), chunk("x"), chunk("a"), chunk("a")));

        assertThat(result.score()).isEqualTo(0.5);
        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
    }

    @Test
    void reciprocalRankScoresAndThreshold() {
        TargetOutput output = retrieved(chunk("x"), chunk("y"), chunk("a"));

        MetricResult low = new ReciprocalRankMetric(5).evaluate(withSources("a"), output);
        MetricResult accepted = new ReciprocalRankMetric(5, 0.3).evaluate(withSources("a"), output);
        MetricResult first = new ReciprocalRankMetric(5).evaluate(withSources("a"), retrieved(chunk("a")));
        MetricResult second = new ReciprocalRankMetric(5).evaluate(withSources("a"), retrieved(chunk("x"), chunk("a")));

        assertThat(low.score()).isCloseTo(1.0 / 3.0, within(1e-9));
        assertThat(low.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(accepted.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(first.score()).isEqualTo(1.0);
        assertThat(second.score()).isEqualTo(0.5);
        assertThat(second.status()).isEqualTo(MetricStatus.PASSED);
    }

    @Test
    void reciprocalRankIsZeroBeyondK() {
        MetricResult atK = new ReciprocalRankMetric(2, 0.5).evaluate(withSources("a"), retrieved(chunk("x"), chunk("a")));
        MetricResult beyond = new ReciprocalRankMetric(2, 0.0).evaluate(withSources("a"),
                retrieved(chunk("x"), chunk("y"), chunk("a")));

        assertThat(atK.score()).isEqualTo(0.5);
        assertThat(beyond.score()).isEqualTo(0.0);
    }

    @Test
    void rankFollowsRetrievedOrderNotScore() {
        TargetOutput output = retrieved(
                new dev.agentworkshq.eval.core.model.RetrievedChunk("1", null, "x", null, "t", 0.99),
                new dev.agentworkshq.eval.core.model.RetrievedChunk("2", null, "a", null, "t", 0.1));

        assertThat(new ReciprocalRankMetric(5).evaluate(withSources("a"), output).score()).isEqualTo(0.5);
    }

    @Test
    void sourcesAreNormalizedForMatching() {
        TargetOutput output = retrieved(chunk("docs/a.pdf"));

        assertThat(new HitAtKMetric(1).evaluate(withSources("docs\\a.pdf"), output).status()).isEqualTo(MetricStatus.PASSED);
        assertThat(new HitAtKMetric(1).evaluate(withSources("./docs/a.pdf"), output).status()).isEqualTo(MetricStatus.PASSED);
        assertThat(new HitAtKMetric(1).evaluate(withSources("  docs/a.pdf "), output).status()).isEqualTo(MetricStatus.PASSED);
        assertThat(new HitAtKMetric(1).evaluate(withSources("docs/a.pdf"), retrieved(chunk(".\\docs\\a.pdf"))).status())
                .isEqualTo(MetricStatus.PASSED);
    }

    @Test
    void matchingIsExactAndCaseSensitive() {
        TargetOutput output = retrieved(chunk("docs/A.pdf"));

        assertThat(new HitAtKMetric(1).evaluate(withSources("docs/a.pdf"), output).status()).isEqualTo(MetricStatus.FAILED);
        assertThat(new HitAtKMetric(1).evaluate(withSources("docs"), output).status()).isEqualTo(MetricStatus.FAILED);
    }

    @Test
    void matchesThroughDocumentId() {
        MetricResult result = new HitAtKMetric(2).evaluate(withSources("doc-7"), retrieved(chunk("x"), chunkWithDocument("doc-7")));

        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.reason()).isEqualTo("first relevant chunk at rank 2 of 2");
    }

    @Test
    void chunkWithoutSourceAndDocumentIdIsNeverRelevant() {
        MetricResult result = new HitAtKMetric(1).evaluate(withSources("a"), retrieved(anonymousChunk()));

        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void floatingPointComparisonUsesEpsilon() {
        TargetOutput output = retrieved(chunk("a"), chunk("a"), chunk("x"));

        MetricResult result = new PrecisionAtKMetric(3, 2.0 / 3.0 + 1e-12).evaluate(withSources("a"), output);

        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
    }

    @Test
    void outputWithOtherFieldsIsIgnored() {
        TargetOutput output = new TargetOutput("", false, List.of(chunk("a")), List.of(), Duration.ZERO, Map.of());

        assertThat(new HitAtKMetric(1).evaluate(withSources("a"), output).status()).isEqualTo(MetricStatus.PASSED);
    }
}
