package dev.agentworkshq.eval.core.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JudgeBudgetTest {

    @Test
    void acquiresAllOrNothing() {
        JudgeBudget budget = new JudgeBudget(5);

        assertThat(budget.tryAcquire(3)).isTrue();
        assertThat(budget.tryAcquire(3)).isFalse();
        assertThat(budget.used()).isEqualTo(3);
        assertThat(budget.tryAcquire(2)).isTrue();
        assertThat(budget.tryAcquire(1)).isFalse();
        assertThat(budget.used()).isEqualTo(5);
        assertThat(budget.max()).isEqualTo(5);
    }

    @Test
    void zeroBudgetAcquiresNothing() {
        assertThat(new JudgeBudget(0).tryAcquire(1)).isFalse();
        assertThat(RunContext.none().budget().max()).isZero();
        assertThat(RunContext.of(7).budget().max()).isEqualTo(7);
    }

    @Test
    void validatesArguments() {
        assertThatThrownBy(() -> new JudgeBudget(-1)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new JudgeBudget(100_001)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new JudgeBudget(5).tryAcquire(0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RunContext(null)).isInstanceOf(ValidationException.class);
        assertThat(new JudgeBudget(100_000).max()).isEqualTo(100_000);
    }

    @Test
    void hugeRequestsDoNotOverflow() {
        JudgeBudget budget = new JudgeBudget(10);
        budget.tryAcquire(5);

        assertThat(budget.tryAcquire(Integer.MAX_VALUE)).isFalse();
        assertThat(budget.used()).isEqualTo(5);
    }

    @Test
    void parallelUseNeverGoesOverTheMaximum() throws Exception {
        JudgeBudget budget = new JudgeBudget(1000);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 16; t++) {
                int step = t % 3 == 0 ? 3 : 1;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        if (budget.tryAcquire(step)) {
                            granted.addAndGet(step);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(budget.used()).isLessThanOrEqualTo(1000).isEqualTo(granted.get());
        assertThat(budget.used()).isGreaterThan(990);
    }
}
