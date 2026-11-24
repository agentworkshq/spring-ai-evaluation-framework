package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class CitationGroundingMetric implements Metric {

    private static final String NAME = "citation_grounded";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
        List<String> cited = output.citedSources();
        if (cited.isEmpty()) {
            return MetricResult.skipped(NAME, "nothing was cited");
        }
        List<Integer> ungrounded = new ArrayList<>();
        for (int i = 0; i < cited.size(); i++) {
            String citation = SourceMatcher.normalize(cited.get(i));
            if (output.retrieved().stream().noneMatch(chunk -> SourceMatcher.matches(citation, chunk))) {
                ungrounded.add(i + 1);
            }
        }
        int grounded = cited.size() - ungrounded.size();
        double score = (double) grounded / cited.size();
        String reason = grounded + " of " + cited.size() + " citations grounded in retrieved chunks";
        if (ungrounded.isEmpty()) {
            return MetricResult.passed(NAME, score, reason);
        }
        String indexes = ungrounded.stream().map(i -> "#" + i).collect(Collectors.joining(", "));
        return MetricResult.failed(NAME, score,
                reason + ", ungrounded citation" + (ungrounded.size() > 1 ? "s " : " ") + indexes);
    }
}
