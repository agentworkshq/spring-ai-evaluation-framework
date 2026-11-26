package dev.agentworkshq.eval.core.compare;

import java.util.List;

public record Comparison(
        String baselineRunId,
        String candidateRunId,
        boolean datasetChanged,
        boolean targetChanged,
        List<ConfigChange> configChanges,
        List<String> onlyInBaseline,
        List<String> onlyInCandidate,
        List<MetricComparison> metrics,
        List<CaseChange> caseChanges,
        List<String> violations) {

    public Comparison {
        configChanges = List.copyOf(configChanges);
        onlyInBaseline = List.copyOf(onlyInBaseline);
        onlyInCandidate = List.copyOf(onlyInCandidate);
        metrics = List.copyOf(metrics);
        caseChanges = List.copyOf(caseChanges);
        violations = List.copyOf(violations);
    }

    public boolean passesGate() {
        return violations.isEmpty();
    }

    @Override
    public String toString() {
        return "Comparison[datasetChanged=" + datasetChanged + ", targetChanged=" + targetChanged + ", configChanges="
                + configChanges.size() + ", onlyInBaseline=" + onlyInBaseline.size() + ", onlyInCandidate="
                + onlyInCandidate.size() + ", metrics=" + metrics.size() + ", caseChanges=" + caseChanges.size()
                + ", violations=" + violations.size() + "]";
    }
}
