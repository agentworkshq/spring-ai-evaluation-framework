package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.RetrievedChunk;
import java.util.List;
import java.util.Set;

public final class ReciprocalRankMetric extends RetrievalMetric {

    public ReciprocalRankMetric(int k) {
        this(k, 0.5);
    }

    public ReciprocalRankMetric(int k, double passThreshold) {
        super("mrr", k, passThreshold);
    }

    @Override
    Scored score(Set<String> expected, List<RetrievedChunk> chunks) {
        int rank = firstRelevantRank(expected, chunks);
        if (rank > 0 && rank <= k) {
            return new Scored(1.0 / rank, "first relevant chunk at rank " + rank + " of " + chunks.size());
        }
        return new Scored(0.0, "no relevant chunk within the first " + Math.min(k, chunks.size()) + " of " + chunks.size());
    }
}
