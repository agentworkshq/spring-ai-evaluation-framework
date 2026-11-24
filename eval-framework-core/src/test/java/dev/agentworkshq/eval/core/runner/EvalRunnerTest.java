package dev.agentworkshq.eval.core.runner;

import static dev.agentworkshq.eval.core.runner.RunnerFixtures.CLOCK;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.MARKER;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.await;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.caseOf;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.dataset;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.echo;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.fast;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.output;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.passing;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.runner;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.runnerThreadsAlive;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.target;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ServiceException;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.CaseResult;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.EvalDataset;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.MetricStatus;
import dev.agentworkshq.eval.core.model.NameRules;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class EvalRunnerTest {

    private static void rejectsWith(String code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BaseException.class)
                .extracting(e -> ((BaseException) e).getErrorCode()).isEqualTo(code);
    }

    private static CaseResult find(EvalRun run, String id) {
        return run.caseResults().stream().filter(r -> r.caseId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void allCasesPassWithMetricsInOrder() {
        EvalRun run = runner().run(dataset("faq", "b", "a"), echo(), List.of(passing("m1"), passing("m2")), fast());

        assertThat(run.caseResults()).extracting(CaseResult::caseId).containsExactly("a", "b");
        assertThat(run.caseResults()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(CaseStatus.COMPLETED);
            assertThat(r.output().answer()).isEqualTo("answer " + r.caseId());
            assertThat(r.metricResults()).extracting(MetricResult::metricName).containsExactly("m1", "m2");
            assertThat(r.metricResults()).allMatch(m -> m.status() == MetricStatus.PASSED);
        });
        assertThat(run.targetName()).isEqualTo("fake-target");
        assertThat(run.startedAt()).isEqualTo(CLOCK.instant());
        assertThat(run.finishedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void resultsAreSortedEvenWhenTargetsFinishInReverseOrder() {
        CountDownLatch cDone = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        var reverse = target(c -> {
            switch (c.id()) {
                case "a" -> await(bDone);
                case "b" -> {
                    await(cDone);
                    bDone.countDown();
                }
                default -> cDone.countDown();
            }
            return output(c.id());
        });

        EvalRun run = runner().run(dataset("d", "c", "a", "b"), reverse, List.of(), fast().withConcurrency(3));

        assertThat(run.caseResults()).extracting(CaseResult::caseId).containsExactly("a", "b", "c");
        assertThat(run.caseResults()).allMatch(r -> r.status() == CaseStatus.COMPLETED);
    }

    @Test
    void concurrencyIsAHardUpperBound() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        CountDownLatch three = new CountDownLatch(3);
        var tracking = target(c -> {
            max.accumulateAndGet(current.incrementAndGet(), Math::max);
            three.countDown();
            await(three);
            current.decrementAndGet();
            return output("x");
        });

        EvalRun run = runner().run(dataset("d", "a", "b", "c", "d", "e", "f", "g", "h"), tracking, List.of(),
                fast().withConcurrency(3));

        assertThat(max.get()).isEqualTo(3);
        assertThat(run.caseResults()).hasSize(8);
    }

    @Test
    void concurrencyOneRunsOneCaseAtATime() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        var tracking = target(c -> {
            max.accumulateAndGet(current.incrementAndGet(), Math::max);
            current.decrementAndGet();
            return output("x");
        });

        runner().run(dataset("d", "a", "b", "c", "d"), tracking, List.of(), fast());

        assertThat(max.get()).isEqualTo(1);
    }

    @Test
    void targetExceptionGivesTargetFailedAndOthersComplete() {
        var target = target(c -> {
            if (c.id().equals("a")) {
                throw new IllegalStateException(MARKER, new RuntimeException(MARKER));
            }
            return output("fine");
        });
        Metric throwing = RunnerFixtures.metric("boom", (c, o) -> {
            throw new IllegalArgumentException(MARKER, new RuntimeException(MARKER));
        });

        EvalRun run = runner().run(dataset("d", "a", "b"), target, List.of(throwing, passing("ok")), fast());

        CaseResult failed = find(run, "a");
        assertThat(failed.status()).isEqualTo(CaseStatus.TARGET_FAILED);
        assertThat(failed.errorMessage()).isEqualTo("IllegalStateException after 1 attempt").doesNotContain(MARKER);
        assertThat(failed.output()).isNull();
        assertThat(failed.metricResults()).isEmpty();
        CaseResult done = find(run, "b");
        assertThat(done.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(done.metricResults()).allSatisfy(m -> assertThat(String.valueOf(m.reason())).doesNotContain(MARKER));
    }

    @Test
    void nullTargetOutputIsATargetFailure() {
        EvalRun run = runner().run(dataset("d", "a"), target(c -> null), List.of(), fast());

        assertThat(find(run, "a").status()).isEqualTo(CaseStatus.TARGET_FAILED);
    }

    @Test
    void flakyTargetSucceedsOnSecondAttempt() {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        var flaky = target(c -> {
            if (calls.computeIfAbsent(c.id(), k -> new AtomicInteger()).incrementAndGet() == 1) {
                throw new IllegalStateException(MARKER);
            }
            return output("ok");
        });

        EvalRun run = runner().run(dataset("d", "a"), flaky, List.of(), fast().withMaxAttempts(2));

        assertThat(find(run, "a").status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(calls.get("a")).hasValue(2);
    }

    @Test
    void alwaysFailingTargetReportsAttemptCountAndRespectsRetryDelay() {
        AtomicInteger calls = new AtomicInteger();
        var failing = target(c -> {
            calls.incrementAndGet();
            throw new UnsupportedOperationException(MARKER);
        });

        EvalRun run = runner().run(dataset("d", "a"), failing, List.of(),
                fast().withMaxAttempts(3).withRetryDelay(Duration.ofMillis(5)));

        CaseResult result = find(run, "a");
        assertThat(result.status()).isEqualTo(CaseStatus.TARGET_FAILED);
        assertThat(result.errorMessage()).isEqualTo("UnsupportedOperationException after 3 attempts");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void metricThatThrowsReturnsNullOrHangsBecomesErrorAndCaseStaysCompleted() {
        CountDownLatch never = new CountDownLatch(1);
        Metric throwing = RunnerFixtures.metric("a_throws", (c, o) -> {
            throw new IllegalStateException(MARKER);
        });
        Metric returnsNull = RunnerFixtures.metric("b_null", (c, o) -> null);
        Metric hangs = RunnerFixtures.metric("c_hangs", (c, o) -> {
            await(never);
            return MetricResult.passed("c_hangs", 1.0, "late");
        });
        Metric wrongName = RunnerFixtures.metric("d_renamed", (c, o) -> MetricResult.passed("other", 1.0, "x"));

        EvalRun run = runner().run(dataset("d", "a"), echo(),
                List.of(hangs, wrongName, returnsNull, throwing, passing("e_ok")),
                fast().withPerMetricTimeout(Duration.ofSeconds(1)));

        CaseResult result = find(run, "a");
        assertThat(result.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(result.output()).isNotNull();
        assertThat(result.metricResults()).extracting(MetricResult::metricName)
                .containsExactly("a_throws", "b_null", "c_hangs", "d_renamed", "e_ok");
        assertThat(result.metricResults()).extracting(MetricResult::status).containsExactly(MetricStatus.ERROR,
                MetricStatus.ERROR, MetricStatus.ERROR, MetricStatus.ERROR, MetricStatus.PASSED);
        assertThat(result.metricResults().get(0).reason()).isEqualTo("metric failed with IllegalStateException");
        assertThat(result.metricResults().get(1).reason()).isEqualTo("metric returned null");
        assertThat(result.metricResults().get(2).reason()).isEqualTo("metric timed out");
        assertThat(result.metricResults()).allSatisfy(m -> assertThat(String.valueOf(m.reason())).doesNotContain(MARKER));
    }

    @Test
    void everyMetricErroringKeepsTheOutput() {
        Metric throwing = RunnerFixtures.metric("m", (c, o) -> {
            throw new IllegalStateException();
        });

        EvalRun run = runner().run(dataset("d", "a"), echo(), List.of(throwing), fast());

        assertThat(find(run, "a").status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(find(run, "a").output().answer()).isEqualTo("answer a");
    }

    @Test
    void rejectsMoreSelectedCasesThanMaxCases() {
        rejectsWith("EVAL_E013", () -> runner().run(dataset("d", "a", "b", "c"), echo(), List.of(), fast().withMaxCases(2)));
    }

    @Test
    void maxCasesAppliesToTheSelectionNotTheWholeDataset() {
        EvalDataset d = new EvalDataset("d", List.of(caseOf("a", "x"), caseOf("b", "x"), caseOf("c", "y")));

        EvalRun run = runner().run(d, echo(), List.of(), fast().withMaxCases(2).withIncludeTags(Set.of("x")));

        assertThat(run.caseResults()).extracting(CaseResult::caseId).containsExactly("a", "b");
    }

    @Test
    void tagFilterSelectsCasesWithAtLeastOneTagAndRecordsCounts() {
        EvalDataset d = new EvalDataset("d", List.of(caseOf("a", "x"), caseOf("b", "y"), caseOf("c", "x", "y"), caseOf("d")));

        EvalRun run = runner().run(d, echo(), List.of(), fast().withIncludeTags(Set.of("x")));

        assertThat(run.caseResults()).extracting(CaseResult::caseId).containsExactly("a", "c");
        assertThat(run.config()).containsEntry("runner.selectedCases", "2").containsEntry("dataset.caseCount", "4")
                .containsEntry("runner.includeTags", "x");
        assertThat(run.datasetFingerprint()).isEqualTo(d.fingerprint());
        EvalRun both = runner().run(d, echo(), List.of(), fast().withIncludeTags(Set.of("y", "x")));
        assertThat(both.caseResults()).extracting(CaseResult::caseId).containsExactly("a", "b", "c");
        assertThat(both.config()).containsEntry("runner.includeTags", "x,y");
    }

    @Test
    void emptySelectionIsRejected() {
        rejectsWith("EVAL_E014", () -> runner().run(dataset("d", "a"), echo(), List.of(),
                fast().withIncludeTags(Set.of("missing"))));
    }

    @Test
    void duplicateMetricNamesAreRejectedWithTheNames() {
        assertThatThrownBy(() -> runner().run(dataset("d", "a"), echo(), List.of(passing("m"), passing("n"), passing("m")), fast()))
                .isInstanceOf(BaseException.class).hasMessageContaining("m")
                .extracting(e -> ((BaseException) e).getErrorCode()).isEqualTo("EVAL_E015");
    }

    @Test
    void nullAndBlankArgumentsAreRejected() {
        EvalDataset d = dataset("d", "a");
        List<Metric> none = List.of();
        List<Metric> withNull = new ArrayList<>();
        withNull.add(null);
        var blankName = target(c -> output("x"));
        var unnamed = new EvalTarget() {
            @Override
            public String name() {
                return " ";
            }

            @Override
            public TargetOutput run(EvalCase evalCase) {
                return output("x");
            }
        };

        rejectsWith("EVAL_E012", () -> runner().run(null, echo(), none, fast()));
        rejectsWith("EVAL_E012", () -> runner().run(d, null, none, fast()));
        rejectsWith("EVAL_E012", () -> runner().run(d, blankName, null, fast()));
        rejectsWith("EVAL_E012", () -> runner().run(d, blankName, none, null));
        rejectsWith("EVAL_E012", () -> runner().run(d, blankName, none, fast(), null));
        rejectsWith("EVAL_E012", () -> runner().run(d, unnamed, none, fast()));
        rejectsWith("EVAL_E012", () -> runner().run(d, echo(), withNull, fast()));
        rejectsWith("EVAL_E012", () -> new EvalRunner(null));
    }

    @Test
    void generatedRunIdIsExactAndUsesTheClock() {
        EvalRun run = runner().run(dataset("faq", "a"), echo(), List.of(), fast());

        assertThat(run.runId()).isEqualTo("faq-20260102T030405678");
    }

    @Test
    void generatedRunIdIsTruncatedToFitNameRules() {
        String name = "a".repeat(100);

        EvalRun run = runner().run(dataset(name, "a"), echo(), List.of(), fast());

        assertThat(run.runId()).hasSize(100).isEqualTo("a".repeat(81) + "-20260102T030405678");
        assertThat(NameRules.isValid(run.runId())).isTrue();
    }

    @Test
    void explicitRunIdIsKept() {
        assertThat(runner().run(dataset("d", "a"), echo(), List.of(), fast().withRunId("my-run")).runId()).isEqualTo("my-run");
    }

    @Test
    void sameInputWithFixedClockGivesEqualRuns() {
        EvalDataset d = dataset("d", "a", "b", "c");
        List<Metric> metrics = List.of(passing("m1"), passing("m2"));

        EvalRun first = runner().run(d, echo(), metrics, fast().withConcurrency(2));
        EvalRun second = runner().run(d, echo(), metrics, fast().withConcurrency(2));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void configSnapshotHasExactlyTheDocumentedKeys() {
        RunConfig config = fast().withConcurrency(2).withMaxAttempts(2).withMaxCases(10)
                .withLabels(Map.of("team", "search", "env", "ci"));

        EvalRun run = runner().run(dataset("d", "a"), echo(), List.of(passing("zeta"), passing("alpha")), config);

        assertThat(run.config()).containsOnlyKeys("runner.concurrency", "runner.perCaseTimeoutMs",
                "runner.perMetricTimeoutMs", "runner.totalTimeoutMs", "runner.maxAttempts", "runner.maxCases",
                "runner.includeTags", "runner.selectedCases", "runner.abandonedCalls", "runner.maxJudgeCalls",
                "runner.judgeCallsUsed", "dataset.caseCount", "metrics",
                "library.version", "label.team", "label.env");
        assertThat(run.config()).containsEntry("runner.concurrency", "2").containsEntry("runner.perCaseTimeoutMs", "10000")
                .containsEntry("runner.perMetricTimeoutMs", "10000").containsEntry("runner.totalTimeoutMs", "1800000")
                .containsEntry("runner.maxAttempts", "2").containsEntry("runner.maxCases", "10")
                .containsEntry("runner.includeTags", "").containsEntry("runner.selectedCases", "1")
                .containsEntry("runner.abandonedCalls", "0").containsEntry("dataset.caseCount", "1")
                .containsEntry("metrics", "alpha,zeta").containsEntry("label.team", "search")
                .containsEntry("label.env", "ci");
        assertThat(run.config().get("library.version")).isNotBlank();
    }

    @Test
    void listenerIsCalledOncePerCase() {
        List<String> ids = Collections.synchronizedList(new ArrayList<>());
        Set<Integer> counts = ConcurrentHashMap.newKeySet();
        AtomicInteger totals = new AtomicInteger();

        runner().run(dataset("d", "a", "b", "c", "d"), echo(), List.of(), fast().withConcurrency(2),
                (result, completed, total) -> {
                    ids.add(result.caseId());
                    counts.add(completed);
                    totals.addAndGet(total);
                });

        assertThat(ids).containsExactlyInAnyOrder("a", "b", "c", "d");
        assertThat(counts).containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(totals.get()).isEqualTo(16);
    }

    @Test
    void throwingListenerDoesNotBreakTheRun() {
        AtomicInteger calls = new AtomicInteger();

        EvalRun run = runner().run(dataset("d", "a", "b"), echo(), List.of(), fast(), (result, completed, total) -> {
            calls.incrementAndGet();
            throw new IllegalStateException(MARKER);
        });

        assertThat(calls.get()).isEqualTo(2);
        assertThat(run.caseResults()).allMatch(r -> r.status() == CaseStatus.COMPLETED);
    }

    @Test
    void interruptingTheCallerCancelsTheRunAndKeepsTheFlag() throws InterruptedException {
        CountDownLatch inTarget = new CountDownLatch(1);
        CountDownLatch never = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean flag = new AtomicBoolean();
        var blocking = target(c -> {
            inTarget.countDown();
            try {
                never.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException();
            }
            return output("x");
        });
        Thread caller = new Thread(() -> {
            try {
                runner().run(dataset("d", "a", "b"), blocking, List.of(), RunConfig.defaults());
            } catch (Throwable t) {
                thrown.set(t);
            }
            flag.set(Thread.currentThread().isInterrupted());
        });

        caller.start();
        await(inTarget);
        caller.interrupt();
        caller.join(5000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) thrown.get()).getErrorCode()).isEqualTo("EVAL_E016");
        assertThat(flag.get()).isTrue();
        assertThat(runnerThreadsAlive()).isFalse();
    }

    @Test
    void noRunnerThreadsSurviveNormalAndFailingRuns() {
        runner().run(dataset("d", "a", "b"), echo(), List.of(passing("m")), fast().withConcurrency(2));
        assertThat(runnerThreadsAlive()).isFalse();

        var failing = target(c -> {
            throw new IllegalStateException();
        });
        runner().run(dataset("d", "a", "b"), failing, List.of(), fast().withConcurrency(2));
        assertThat(runnerThreadsAlive()).isFalse();
    }
}
