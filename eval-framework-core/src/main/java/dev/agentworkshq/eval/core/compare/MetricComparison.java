package dev.agentworkshq.eval.core.compare;

public record MetricComparison(
        String metricName,
        MetricVerdict verdict,
        double tolerance,
        MetricAggregate baseline,
        MetricAggregate candidate) {
}
