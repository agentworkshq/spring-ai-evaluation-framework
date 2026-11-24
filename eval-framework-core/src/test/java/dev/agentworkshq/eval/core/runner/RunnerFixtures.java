package dev.agentworkshq.eval.core.runner;

import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.EvalDataset;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

final class RunnerFixtures {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05.678Z"), ZoneOffset.UTC);
    static final String MARKER = "SECRET-MARKER-TEXT";

    private RunnerFixtures() {
    }

    static EvalCase caseOf(String id, String... tags) {
        return new EvalCase(id, "question " + id, null, null, false, List.of(tags), null, null, null);
    }

    static EvalDataset dataset(String name, String... ids) {
        List<EvalCase> cases = new ArrayList<>();
        for (String id : ids) {
            cases.add(caseOf(id));
        }
        return new EvalDataset(name, cases);
    }

    static TargetOutput output(String answer) {
        return new TargetOutput(answer, true, null, null, Duration.ZERO, null);
    }

    static EvalTarget target(Function<EvalCase, TargetOutput> body) {
        return new EvalTarget() {
            @Override
            public String name() {
                return "fake-target";
            }

            @Override
            public TargetOutput run(EvalCase evalCase) {
                return body.apply(evalCase);
            }
        };
    }

    static EvalTarget echo() {
        return target(c -> output("answer " + c.id()));
    }

    static Metric metric(String name, BiFunction<EvalCase, TargetOutput, MetricResult> body) {
        return new Metric() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
                return body.apply(evalCase, output);
            }
        };
    }

    static Metric passing(String name) {
        return metric(name, (c, o) -> MetricResult.passed(name, 1.0, "ok"));
    }

    static EvalRunner runner() {
        return new EvalRunner(CLOCK, 50);
    }

    static RunConfig fast() {
        return RunConfig.defaults().withPerCaseTimeout(Duration.ofMillis(200)).withPerMetricTimeout(Duration.ofMillis(200));
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static void awaitUninterruptibly(CountDownLatch latch) {
        while (true) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // the target under test deliberately ignores interrupts
            }
        }
    }

    static boolean runnerThreadsAlive() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            boolean alive = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.isAlive() && t.getName().startsWith("eval-runner"));
            if (!alive || System.nanoTime() > deadline) {
                return alive;
            }
            Thread.onSpinWait();
        }
    }
}
