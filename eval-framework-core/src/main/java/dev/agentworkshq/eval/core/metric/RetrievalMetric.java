package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.List;
import java.util.Set;

abstract class RetrievalMetric implements Metric {

    private final String name;
    private final double passThreshold;
    final int k;

    RetrievalMetric(String prefix, int k, double passThreshold) {
        this.k = MetricArgs.k(k);
        this.passThreshold = MetricArgs.threshold(passThreshold);
        this.name = prefix + "_at_" + k;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
        Set<String> expected = SourceMatcher.expected(evalCase);
        if (evalCase.expectNoAnswer() || expected.isEmpty()) {
            return MetricResult.skipped(name, "case has no expected sources");
        }
        List<RetrievedChunk> chunks = output.retrieved();
        if (chunks.isEmpty()) {
            return MetricResult.failed(name, 0.0, "nothing was retrieved");
        }
        Scored scored = score(expected, chunks);
        if (MetricArgs.reaches(scored.score(), passThreshold)) {
            return MetricResult.passed(name, scored.score(), scored.reason());
        }
        return MetricResult.failed(name, scored.score(), scored.reason());
    }

    abstract Scored score(Set<String> expected, List<RetrievedChunk> chunks);

    final int firstRelevantRank(Set<String> expected, List<RetrievedChunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            if (SourceMatcher.isRelevant(expected, chunks.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    record Scored(double score, String reason) {
    }
}
