package dev.agentworkshq.eval.core.compare;

public record MetricComparison(
        String metricName,
        MetricVerdict verdict,
        double tolerance,
        MetricAggregate baseline,
        MetricAggregate candidate) {

    @Override
    public String toString() {
        return "MetricComparison[verdict=" + verdict + ", tolerance=" + tolerance + ", baseline=" + baseline
                + ", candidate=" + candidate + "]";
    }
}
