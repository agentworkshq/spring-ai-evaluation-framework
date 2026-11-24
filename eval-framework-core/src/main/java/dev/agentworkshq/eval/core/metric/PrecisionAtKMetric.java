package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.RetrievedChunk;
import java.util.List;
import java.util.Set;

public final class PrecisionAtKMetric extends RetrievalMetric {

    public PrecisionAtKMetric(int k, double passThreshold) {
        super("precision", k, passThreshold);
    }

    @Override
    Scored score(Set<String> expected, List<RetrievedChunk> chunks) {
        List<RetrievedChunk> top = chunks.subList(0, Math.min(k, chunks.size()));
        long relevant = top.stream().filter(chunk -> SourceMatcher.isRelevant(expected, chunk)).count();
        return new Scored((double) relevant / top.size(), relevant + " of " + top.size() + " chunks relevant");
    }
}
