package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.Set;

public final class CitationMetric implements Metric {

    private static final String NAME = "citation";

    private final double passThreshold;

    public CitationMetric() {
        this(1.0);
    }

    public CitationMetric(double passThreshold) {
        this.passThreshold = MetricArgs.threshold(passThreshold);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
        Set<String> expected = SourceMatcher.expected(evalCase);
        if (evalCase.expectNoAnswer() || expected.isEmpty()) {
            return MetricResult.skipped(NAME, "case has no expected sources");
        }
        Set<String> cited = SourceMatcher.normalizedSet(output.citedSources());
        long found = expected.stream().filter(cited::contains).count();
        double score = (double) found / expected.size();
        String reason = found + " of " + expected.size() + " expected sources cited";
        return MetricArgs.reaches(score, passThreshold)
                ? MetricResult.passed(NAME, score, reason)
                : MetricResult.failed(NAME, score, reason);
    }
}
