package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;

final class MetricArgs {

    static final double EPSILON = 1e-9;

    private MetricArgs() {
    }

    static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E010, detail);
    }

    static int k(int k) {
        if (k < 1 || k > 100) {
            throw bad("k must be between 1 and 100");
        }
        return k;
    }

    static double threshold(double value) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw bad("passThreshold must be between 0 and 1");
        }
        return value;
    }

    static boolean reaches(double score, double threshold) {
        return score + EPSILON >= threshold;
    }
}
