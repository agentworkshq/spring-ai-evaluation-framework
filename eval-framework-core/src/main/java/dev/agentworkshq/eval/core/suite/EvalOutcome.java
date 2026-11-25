package dev.agentworkshq.eval.core.suite;

import dev.agentworkshq.eval.core.compare.Comparison;
import dev.agentworkshq.eval.core.compare.RunSummary;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.store.StoredRun;
import java.util.List;

public record EvalOutcome(
        EvalRun run,
        StoredRun stored,
        RunSummary summary,
        Comparison comparison,
        String baselineRunId,
        List<String> violations,
        String consoleReport,
        String markdownReport,
        List<String> reportFiles) {

    public EvalOutcome {
        violations = List.copyOf(violations.stream().sorted().toList());
        reportFiles = List.copyOf(reportFiles);
    }

    public boolean passesGate() {
        return violations.isEmpty();
    }

    @Override
    public String toString() {
        return "EvalOutcome[runId=" + run.runId() + ", cases=" + stored.cases().size() + ", violations="
                + violations.size() + ", gate=" + (passesGate() ? "PASSED" : "FAILED") + "]";
    }
}
