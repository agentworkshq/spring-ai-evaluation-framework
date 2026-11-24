package dev.agentworkshq.eval.core.runner;

import dev.agentworkshq.eval.core.model.CaseResult;

@FunctionalInterface
public interface RunListener {

    void onCaseCompleted(CaseResult result, int completed, int total);
}
