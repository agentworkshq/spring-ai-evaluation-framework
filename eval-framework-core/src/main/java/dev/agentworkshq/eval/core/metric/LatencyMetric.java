package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.time.Duration;

public final class LatencyMetric implements Metric {

    private static final String NAME = "latency";

    private final Duration max;

    public LatencyMetric(Duration max) {
        if (max == null || max.isZero() || max.isNegative()) {
            throw MetricArgs.bad("max latency must be positive");
        }
        this.max = max;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
        if (output.latency().compareTo(max) <= 0) {
            return MetricResult.passed(NAME, 1.0, "latency within " + max.toMillis() + " ms");
        }
        return MetricResult.failed(NAME, 0.0, "latency above " + max.toMillis() + " ms");
    }
}
