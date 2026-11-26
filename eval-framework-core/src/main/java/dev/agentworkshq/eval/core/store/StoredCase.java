package dev.agentworkshq.eval.core.store;

import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.NameRules;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

public record StoredCase(
        String caseId,
        CaseStatus status,
        String errorMessage,
        long durationMs,
        Boolean answered,
        Integer answerChars,
        Long latencyMs,
        Integer retrievedCount,
        List<MetricResult> metricResults) {

    public StoredCase {
        NameRules.require(caseId, "caseId");
        if (status == null) {
            throw Checks.bad("field 'status' must not be null");
        }
        boolean failed = status == CaseStatus.TARGET_FAILED || status == CaseStatus.TIMED_OUT;
        if (failed && (errorMessage == null || errorMessage.isBlank())) {
            throw Checks.bad("field 'errorMessage' is required for a failed or timed out case");
        }
        Checks.maxLength(errorMessage, "errorMessage", 500);
        Checks.notNegative(durationMs, "durationMs");
        Checks.notNegative(answerChars, "answerChars");
        Checks.notNegative(latencyMs, "latencyMs");
        Checks.notNegative(retrievedCount, "retrievedCount");
        if (metricResults == null) {
            metricResults = List.of();
        }
        if (metricResults.stream().anyMatch(r -> r == null)) {
            throw Checks.bad("field 'metricResults' must not contain null entries");
        }
        if (new HashSet<>(metricResults.stream().map(MetricResult::metricName).toList()).size() != metricResults.size()) {
            throw Checks.bad("field 'metricResults' has a duplicate metric name");
        }
        metricResults = metricResults.stream().sorted(Comparator.comparing(MetricResult::metricName)).toList();
    }

    @Override
    public String toString() {
        return "StoredCase[caseId=" + caseId + ", status=" + status + ", durationMs=" + durationMs + ", metricResults="
                + metricResults.size() + "]";
    }
}
