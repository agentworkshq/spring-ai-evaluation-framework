package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.RetrievedChunk;
import java.util.List;
import java.util.Set;

public final class RecallAtKMetric extends RetrievalMetric {

    public RecallAtKMetric(int k) {
        this(k, 1.0);
    }

    public RecallAtKMetric(int k, double passThreshold) {
        super("recall", k, passThreshold);
    }

    @Override
    Scored score(Set<String> expected, List<RetrievedChunk> chunks) {
        List<RetrievedChunk> top = chunks.subList(0, Math.min(k, chunks.size()));
        int covered = 0;
        for (String source : expected) {
            if (top.stream().anyMatch(chunk -> SourceMatcher.matches(source, chunk))) {
                covered++;
            }
        }
        return new Scored((double) covered / expected.size(),
                covered + " of " + expected.size() + " expected sources covered in the first " + top.size() + " chunks");
    }
}
