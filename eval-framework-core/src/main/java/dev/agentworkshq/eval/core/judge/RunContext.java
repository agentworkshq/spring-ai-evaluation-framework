package dev.agentworkshq.eval.core.judge;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;

public record RunContext(JudgeBudget budget) {

    public RunContext {
        if (budget == null) {
            throw new ValidationException(ErrorMessages.EVAL_E010, "budget must not be null");
        }
    }

    public static RunContext of(int maxJudgeCalls) {
        return new RunContext(new JudgeBudget(maxJudgeCalls));
    }

    public static RunContext none() {
        return of(0);
    }
}
