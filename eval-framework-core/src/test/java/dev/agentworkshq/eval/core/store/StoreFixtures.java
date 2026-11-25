package dev.agentworkshq.eval.core.store;

import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.EvalDataset;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.runner.EvalRunner;
import dev.agentworkshq.eval.core.runner.RunConfig;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class StoreFixtures {

    static final Instant T0 = Instant.parse("2026-01-02T03:04:05.678Z");

    private StoreFixtures() {
    }

    static StoredRun golden() {
        StoredCase a = new StoredCase("a", CaseStatus.COMPLETED, null, 12, true, 5, 3L, 2, List.of(
                MetricResult.skipped("hit_at_3", "no expected sources"),
                MetricResult.passed("answer_contains", 1.0, "all phrases present"),
                MetricResult.failed("faithfulness", 1.0 / 3, "judge said \"no\"")));
        StoredCase b = new StoredCase("b", CaseStatus.TARGET_FAILED, "IllegalStateException after 1 attempts", 7,
                null, null, null, null, List.of());
        StoredCase c = new StoredCase("c", CaseStatus.COMPLETED, null, 0, false, 0, 0L, 0, List.of(
                MetricResult.error("faithfulness", "invalid judge reply"),
                MetricResult.passed("answer_contains", 0.1, "")));
        return new StoredRun("run-1", "ds", "fp-abc", "target-a", T0, Instant.parse("2026-01-02T03:04:06Z"),
                Map.of("runner.concurrency", "1", "metric.faithfulness.judge", "judge-1"), List.of(c, a, b));
    }

    static StoredCase completed(String id, MetricResult... results) {
        return new StoredCase(id, CaseStatus.COMPLETED, null, 1, true, 1, 1L, 0, List.of(results));
    }

    static StoredRun run(String runId, Instant startedAt, StoredCase... cases) {
        return new StoredRun(runId, "ds", "fp", "target", startedAt, startedAt.plusSeconds(1), Map.of(), List.of(cases));
    }

    static EvalRun evalRunWithMarker(String marker) {
        EvalTarget target = new EvalTarget() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public TargetOutput run(EvalCase evalCase) {
                return new TargetOutput(marker + " answer", true,
                        List.of(new RetrievedChunk("c1", "d1", "s.md", 1, marker + " chunk", 0.5)),
                        List.of(), Duration.ZERO, Map.of());
            }
        };
        Metric metric = new Metric() {
            @Override
            public String name() {
                return "m";
            }

            @Override
            public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
                return MetricResult.passed("m", 1.0, "ok");
            }
        };
        EvalDataset dataset = new EvalDataset("ds", List.of(
                new EvalCase("a", marker + " question", marker + " expected", null, false, null, null, null, null)));
        return new EvalRunner().run(dataset, target, List.of(metric), RunConfig.defaults());
    }

    static String resource(String name) {
        try (InputStream in = StoreFixtures.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
