package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;

public interface Metric {

    String name();

    MetricResult evaluate(EvalCase evalCase, TargetOutput output);
}
