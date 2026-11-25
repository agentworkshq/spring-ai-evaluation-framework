package dev.agentworkshq.eval.core.compare;

import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.store.StoredCase;
import dev.agentworkshq.eval.core.store.StoredRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class CompareFixtures {

    static final Instant T0 = Instant.parse("2026-01-02T03:04:05Z");

    private CompareFixtures() {
    }

    static StoredCase done(String id, MetricResult... results) {
        return new StoredCase(id, CaseStatus.COMPLETED, null, 1, true, 1, 1L, 0, List.of(results));
    }

    static StoredCase failedTarget(String id, String message) {
        return new StoredCase(id, CaseStatus.TARGET_FAILED, message, 1, null, null, null, null, List.of());
    }

    static StoredRun run(String runId, StoredCase... cases) {
        return run(runId, Map.of(), cases);
    }

    static StoredRun run(String runId, Map<String, String> config, StoredCase... cases) {
        return new StoredRun(runId, "ds", "fp", "target", T0, T0.plusSeconds(1), config, List.of(cases));
    }

    static StoredRun runOf(String runId, String dataset, String fingerprint, String target, StoredCase... cases) {
        return new StoredRun(runId, dataset, fingerprint, target, T0, T0.plusSeconds(1), Map.of(), List.of(cases));
    }

    static MetricResult pass(String metric, double score) {
        return MetricResult.passed(metric, score, "ok");
    }

    static MetricResult fail(String metric, double score) {
        return MetricResult.failed(metric, score, "no");
    }

    static MetricResult err(String metric) {
        return MetricResult.error(metric, "boom");
    }

    static MetricResult skip(String metric) {
        return MetricResult.skipped(metric, "n/a");
    }
}
