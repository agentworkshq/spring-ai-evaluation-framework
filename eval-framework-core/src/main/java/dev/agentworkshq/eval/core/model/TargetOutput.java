package dev.agentworkshq.eval.core.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record TargetOutput(
        String answer,
        boolean answered,
        List<RetrievedChunk> retrieved,
        List<String> citedSources,
        Duration latency,
        Map<String, String> attributes) {

    public TargetOutput {
        if (answer == null) {
            throw Checks.bad("field 'answer' must not be null");
        }
        retrieved = Checks.list(retrieved, "retrieved", Integer.MAX_VALUE);
        citedSources = Checks.list(citedSources, "citedSources", Integer.MAX_VALUE);
        if (latency == null || latency.isNegative()) {
            throw Checks.bad("field 'latency' must not be null or negative");
        }
        attributes = Checks.sortedMap(attributes, "attributes", Integer.MAX_VALUE);
    }

    @Override
    public String toString() {
        return "TargetOutput[answerLength=" + answer.length() + ", answered=" + answered + ", retrieved="
                + retrieved.size() + ", citedSources=" + citedSources.size() + ", latency=" + latency + "]";
    }
}
