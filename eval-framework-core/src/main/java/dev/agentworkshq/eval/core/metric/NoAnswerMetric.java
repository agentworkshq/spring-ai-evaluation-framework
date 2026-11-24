package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;

public final class NoAnswerMetric implements Metric {

    private static final String NAME = "no_answer";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
        boolean shouldAnswer = !evalCase.expectNoAnswer();
        if (output.answered() == shouldAnswer) {
            return MetricResult.passed(NAME, 1.0, shouldAnswer ? "answered as expected" : "abstained as expected");
        }
        return MetricResult.failed(NAME, 0.0,
                shouldAnswer ? "abstained but should have answered" : "answered but should have abstained");
    }
}
