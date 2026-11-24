package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.RetrievedChunk;
import java.util.List;
import java.util.Set;

public final class HitAtKMetric extends RetrievalMetric {

    public HitAtKMetric(int k) {
        super("hit", k, 1.0);
    }

    @Override
    Scored score(Set<String> expected, List<RetrievedChunk> chunks) {
        int rank = firstRelevantRank(expected, chunks);
        if (rank > 0 && rank <= k) {
            return new Scored(1.0, "first relevant chunk at rank " + rank + " of " + chunks.size());
        }
        String suffix = rank > 0 ? "; first relevant chunk at rank " + rank : "";
        return new Scored(0.0, "no relevant chunk within the first " + Math.min(k, chunks.size()) + " of "
                + chunks.size() + suffix);
    }
}
