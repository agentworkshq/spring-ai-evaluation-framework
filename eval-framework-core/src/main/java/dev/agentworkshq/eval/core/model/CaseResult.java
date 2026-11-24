package dev.agentworkshq.eval.core.model;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public record CaseResult(
        String caseId,
        CaseStatus status,
        TargetOutput output,
        String errorMessage,
        List<MetricResult> metricResults,
        Duration duration) {

    public CaseResult {
        NameRules.require(caseId, "caseId");
        if (status == null) {
            throw Checks.bad("field 'status' must not be null");
        }
        if (status == CaseStatus.COMPLETED && output == null) {
            throw Checks.bad("field 'output' is required for a completed case");
        }
        boolean failed = status == CaseStatus.TARGET_FAILED || status == CaseStatus.TIMED_OUT;
        if (failed && (errorMessage == null || errorMessage.isBlank())) {
            throw Checks.bad("field 'errorMessage' is required for a failed or timed out case");
        }
        Checks.maxLength(errorMessage, "errorMessage", 500);
        metricResults = Checks.list(metricResults, "metricResults", Integer.MAX_VALUE);
        if (new HashSet<>(metricResults.stream().map(MetricResult::metricName).toList()).size() != metricResults.size()) {
            throw Checks.bad("field 'metricResults' has a duplicate metric name");
        }
        metricResults = metricResults.stream().sorted(Comparator.comparing(MetricResult::metricName)).toList();
        if (duration == null || duration.isNegative()) {
            throw Checks.bad("field 'duration' must not be null or negative");
        }
    }
}
