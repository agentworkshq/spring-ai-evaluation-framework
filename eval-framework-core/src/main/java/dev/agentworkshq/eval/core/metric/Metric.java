package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.judge.RunContext;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.Map;

public interface Metric {

    String name();

    MetricResult evaluate(EvalCase evalCase, TargetOutput output);

    default MetricResult evaluate(EvalCase evalCase, TargetOutput output, RunContext context) {
        return evaluate(evalCase, output);
    }

    default Map<String, String> runConfig() {
        return Map.of();
    }
}
