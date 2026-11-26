package dev.agentworkshq.eval.core.compare;

public record CaseChange(
        String caseId,
        String metricName,
        ChangeKind kind,
        String baselineStatus,
        Double baselineScore,
        String candidateStatus,
        Double candidateScore) {

    @Override
    public String toString() {
        return "CaseChange[kind=" + kind + ", baselineScore=" + baselineScore + ", candidateScore=" + candidateScore + "]";
    }
}
