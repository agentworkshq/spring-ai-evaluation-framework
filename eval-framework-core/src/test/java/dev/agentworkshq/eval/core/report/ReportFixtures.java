package dev.agentworkshq.eval.core.report;

import dev.agentworkshq.eval.core.compare.Comparison;
import dev.agentworkshq.eval.core.compare.ComparisonConfig;
import dev.agentworkshq.eval.core.compare.RunComparator;
import dev.agentworkshq.eval.core.compare.RunSummary;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.store.StoredCase;
import dev.agentworkshq.eval.core.store.StoredRun;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ReportFixtures {

    static final Instant T0 = Instant.parse("2026-01-02T03:04:05Z");

    private ReportFixtures() {
    }

    static StoredCase done(String id, MetricResult... results) {
        return new StoredCase(id, CaseStatus.COMPLETED, null, 1, true, 1, 1L, 0, List.of(results));
    }

    static StoredRun run(String runId, String target, Map<String, String> config, List<StoredCase> cases) {
        return new StoredRun(runId, "ds", "fp", target, T0, T0.plusSeconds(1), config, cases);
    }

    static StoredRun baseline() {
        return run("base-1", "target-a",
                Map.of("runner.concurrency", "1", "metric.faith.promptVersion", "1", "metric.faith.judge", "judge-a",
                        "label.team", "core"),
                List.of(
                        done("a", MetricResult.passed("m_reg", 1.0, "ok"), MetricResult.passed("cov", 0.9, "ok"),
                                MetricResult.passed("faith", 1.0, "ok")),
                        done("b", MetricResult.passed("m_score", 0.9, "ok"), MetricResult.skipped("only_skipped", "n/a")),
                        done("c", MetricResult.failed("m_reg", 0.0, "no"), MetricResult.passed("m_score", 0.9, "ok")),
                        done("d", MetricResult.passed("m_reg", 1.0, "ok"))));
    }

    static StoredRun candidate() {
        return candidate(false);
    }

    static StoredRun candidate(boolean reversed) {
        List<StoredCase> cases = new ArrayList<>(List.of(
                done("a", MetricResult.failed("m_reg", 0.0, "no"), MetricResult.skipped("cov", "n/a"),
                        MetricResult.failed("faith", 0.2, "no")),
                done("b", MetricResult.passed("m_score", 0.5, "ok"), MetricResult.skipped("only_skipped", "n/a")),
                done("c", MetricResult.passed("m_reg", 1.0, "ok"), MetricResult.passed("m_score", 0.9, "ok")),
                new StoredCase("d", CaseStatus.TARGET_FAILED, "IllegalStateException after 1 attempts", 1, null, null,
                        null, null, List.of())));
        if (reversed) {
            java.util.Collections.reverse(cases);
        }
        return run("cand-1", "target-a",
                Map.of("runner.concurrency", "2", "metric.faith.promptVersion", "2", "metric.faith.judge", "judge-a",
                        "label.team", "core"),
                cases);
    }

    static Comparison comparison() {
        return RunComparator.compare(baseline(), candidate(), ComparisonConfig.defaults());
    }

    static RunSummary summary() {
        return RunSummary.of(candidate());
    }

    static String resource(String name) {
        try (InputStream in = ReportFixtures.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
