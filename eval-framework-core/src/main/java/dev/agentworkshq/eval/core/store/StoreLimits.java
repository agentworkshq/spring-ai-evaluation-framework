package dev.agentworkshq.eval.core.store;

public record StoreLimits(int maxFileBytes, int maxRuns, int maxNestingDepth) {

    public StoreLimits {
        if (maxFileBytes <= 0 || maxRuns <= 0 || maxNestingDepth <= 0) {
            throw new IllegalArgumentException("store limits must be positive");
        }
    }

    public static StoreLimits defaults() {
        return new StoreLimits(20 * 1024 * 1024, 10_000, 20);
    }
}
