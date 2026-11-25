package dev.agentworkshq.eval.core.store;

import java.time.Instant;

public record RunHeader(String runId, String datasetName, String targetName, Instant startedAt, int caseCount) {
}
