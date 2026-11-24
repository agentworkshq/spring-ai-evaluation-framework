package dev.agentworkshq.eval.core.judge;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class JudgeBudget {

    private final int max;
    private final AtomicInteger used = new AtomicInteger();

    public JudgeBudget(int max) {
        if (max < 0 || max > 100_000) {
            throw new ValidationException(ErrorMessages.EVAL_E010, "judge budget must be between 0 and 100000");
        }
        this.max = max;
    }

    public boolean tryAcquire(int n) {
        if (n < 1) {
            throw new ValidationException(ErrorMessages.EVAL_E010, "judge calls to acquire must be at least 1");
        }
        int current;
        do {
            current = used.get();
            if ((long) current + n > max) {
                return false;
            }
        } while (!used.compareAndSet(current, current + n));
        return true;
    }

    public int used() {
        return used.get();
    }

    public int max() {
        return max;
    }
}
