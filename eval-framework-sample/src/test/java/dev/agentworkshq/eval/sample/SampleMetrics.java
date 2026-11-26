package dev.agentworkshq.eval.sample;

import dev.agentworkshq.eval.core.metric.AnswerContainsMetric;
import dev.agentworkshq.eval.core.metric.CitationGroundingMetric;
import dev.agentworkshq.eval.core.metric.CitationMetric;
import dev.agentworkshq.eval.core.metric.HitAtKMetric;
import dev.agentworkshq.eval.core.metric.LatencyMetric;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.metric.NoAnswerMetric;
import dev.agentworkshq.eval.core.metric.RecallAtKMetric;
import dev.agentworkshq.eval.core.metric.ReciprocalRankMetric;
import java.time.Duration;
import java.util.List;

final class SampleMetrics {

    private static final int K = 3;

    private SampleMetrics() {
    }

    static List<Metric> all() {
        return List.of(
                new HitAtKMetric(K),
                new RecallAtKMetric(K),
                new ReciprocalRankMetric(K),
                new AnswerContainsMetric(),
                new NoAnswerMetric(),
                new CitationMetric(),
                new CitationGroundingMetric(),
                new LatencyMetric(Duration.ofSeconds(30)));
    }
}
