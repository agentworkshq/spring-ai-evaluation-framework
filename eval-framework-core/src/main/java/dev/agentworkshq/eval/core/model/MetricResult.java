package dev.agentworkshq.eval.core.model;

public record MetricResult(String metricName, MetricStatus status, Double score, String reason) {

    private static final int MAX_REASON = 500;

    public MetricResult {
        Checks.required(metricName, "metricName");
        if (status == null) {
            throw Checks.bad("field 'status' must not be null");
        }
        boolean scored = status == MetricStatus.PASSED || status == MetricStatus.FAILED;
        if (scored && (score == null || !(score >= 0.0 && score <= 1.0))) {
            throw Checks.bad("field 'score' must be between 0 and 1 for a passed or failed result");
        }
        if (!scored && score != null) {
            throw Checks.bad("field 'score' must be null for an error or skipped result");
        }
        reason = Text.truncate(reason, MAX_REASON);
    }

    public static MetricResult passed(String metricName, double score, String reason) {
        return new MetricResult(metricName, MetricStatus.PASSED, score, reason);
    }

    public static MetricResult failed(String metricName, double score, String reason) {
        return new MetricResult(metricName, MetricStatus.FAILED, score, reason);
    }

    public static MetricResult error(String metricName, String reason) {
        return new MetricResult(metricName, MetricStatus.ERROR, null, reason);
    }

    public static MetricResult skipped(String metricName, String reason) {
        return new MetricResult(metricName, MetricStatus.SKIPPED, null, reason);
    }
}
