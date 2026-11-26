package dev.agentworkshq.eval.core.runner;

import static dev.agentworkshq.eval.core.runner.RunnerFixtures.awaitUninterruptibly;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.dataset;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.fast;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.output;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.passing;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.runner;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.runnerThreadsAlive;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.target;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.agentworkshq.eval.core.model.CaseResult;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvalRunnerTimeoutTest {

    private static final Duration SHORT = Duration.ofMillis(100);

    private static CaseResult find(EvalRun run, String id) {
        return run.caseResults().stream().filter(r -> r.caseId().equals(id)).findFirst().orElseThrow();
    }

    private static EvalTarget failsAfter(AtomicInteger calls, Duration wait) {
        return target(c -> {
            calls.incrementAndGet();
            try {
                new CountDownLatch(1).await(wait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException();
        });
    }

    @Test
    void theRetryDelayNeverOutlastsTheTotalTimeout() {
        AtomicInteger calls = new AtomicInteger();
        EvalTarget failing = target(c -> {
            calls.incrementAndGet();
            throw new IllegalStateException();
        });

        EvalRun run = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> runner().run(dataset("d", "a", "b"),
                failing, List.of(),
                fast().withMaxAttempts(3).withRetryDelay(Duration.ofDays(365)).withTotalTimeout(SHORT)));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(run.caseResults()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(CaseStatus.SKIPPED_BUDGET);
            assertThat(r.errorMessage()).isNull();
        });
    }

    @Test
    void noAttemptStartsAfterTheTotalDeadline() {
        AtomicInteger calls = new AtomicInteger();

        EvalRun run = runner().run(dataset("d", "a"), failsAfter(calls, SHORT.multipliedBy(3)), List.of(),
                fast().withMaxAttempts(3).withTotalTimeout(SHORT));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(find(run, "a").status()).isEqualTo(CaseStatus.SKIPPED_BUDGET);
    }

    @Test
    void theLastAttemptStillCountsAsFailedAfterTheDeadline() {
        AtomicInteger calls = new AtomicInteger();

        EvalRun run = runner().run(dataset("d", "a"), failsAfter(calls, SHORT.multipliedBy(3)), List.of(),
                fast().withTotalTimeout(SHORT));

        assertThat(find(run, "a").status()).isEqualTo(CaseStatus.TARGET_FAILED);
    }

    @Test
    void aRetryWithinTheTotalTimeoutStillHappens() {
        AtomicInteger calls = new AtomicInteger();
        EvalTarget flaky = target(c -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException();
            }
            return output("x");
        });

        EvalRun run = runner().run(dataset("d", "a"), flaky, List.of(),
                fast().withMaxAttempts(2).withRetryDelay(Duration.ofMillis(5)));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(find(run, "a").status()).isEqualTo(CaseStatus.COMPLETED);
    }

    @Test
    void perCaseTimeoutGivesTimedOutAndTheRunFinishesInBoundedTime() {
        CountDownLatch never = new CountDownLatch(1);
        var blocking = target(c -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException();
            }
            return output("x");
        });
        long start = System.nanoTime();

        EvalRun run = runner().run(dataset("d", "a", "b"), blocking, List.of(passing("m")),
                fast().withConcurrency(2).withPerCaseTimeout(SHORT));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(run.caseResults()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(CaseStatus.TIMED_OUT);
            assertThat(r.errorMessage()).isEqualTo("target exceeded 100 ms");
            assertThat(r.output()).isNull();
            assertThat(r.metricResults()).isEmpty();
        });
        assertThat(run.config()).containsEntry("runner.abandonedCalls", "0");
        assertThat(runnerThreadsAlive()).isFalse();
    }

    @Test
    void aTimeoutIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch never = new CountDownLatch(1);
        var blocking = target(c -> {
            calls.incrementAndGet();
            try {
                never.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException();
            }
            return output("x");
        });

        EvalRun run = runner().run(dataset("d", "a"), blocking, List.of(),
                fast().withPerCaseTimeout(SHORT).withMaxAttempts(3));

        assertThat(find(run, "a").status()).isEqualTo(CaseStatus.TIMED_OUT);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void targetsThatIgnoreInterruptsAreAbandonedAndStopTheRun() {
        CountDownLatch release = new CountDownLatch(1);
        var stubborn = target(c -> {
            awaitUninterruptibly(release);
            return output("late");
        });
        EvalRun run;
        try {
            run = runner().run(dataset("d", "a", "b", "c", "d"), stubborn, List.of(),
                    fast().withPerCaseTimeout(SHORT).withMaxAbandonedCalls(1));
        } finally {
            release.countDown();
        }

        assertThat(run.caseResults()).extracting(CaseResult::status).containsExactly(CaseStatus.TIMED_OUT,
                CaseStatus.TIMED_OUT, CaseStatus.SKIPPED_BUDGET, CaseStatus.SKIPPED_BUDGET);
        assertThat(run.config()).containsEntry("runner.abandonedCalls", "2");
        assertThat(runnerThreadsAlive()).isFalse();
    }

    @Test
    void totalTimeoutSkipsTheRemainingCasesWithoutOutput() {
        long start = System.nanoTime();
        var slow = target(c -> {
            while (System.nanoTime() - start < Duration.ofMillis(1300).toNanos()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new IllegalStateException();
                }
            }
            return output("x");
        });

        EvalRun run = runner().run(dataset("d", "a", "b", "c"), slow, List.of(passing("m")),
                fast().withTotalTimeout(Duration.ofSeconds(1)));

        assertThat(find(run, "a").status()).isEqualTo(CaseStatus.COMPLETED);
        for (String id : List.of("b", "c")) {
            CaseResult skipped = find(run, id);
            assertThat(skipped.status()).isEqualTo(CaseStatus.SKIPPED_BUDGET);
            assertThat(skipped.output()).isNull();
            assertThat(skipped.metricResults()).isEmpty();
            assertThat(skipped.duration()).isEqualTo(Duration.ZERO);
        }
        assertThat(run.caseResults()).hasSize(3);
    }
}
