package dev.agentworkshq.eval.core.dataset;

public record DatasetLimits(int maxFileBytes, int maxCases, int maxLineBytes, int maxNestingDepth) {

    public DatasetLimits {
        if (maxFileBytes <= 0 || maxCases <= 0 || maxLineBytes <= 0 || maxNestingDepth <= 0) {
            throw new IllegalArgumentException("dataset limits must be positive");
        }
    }

    public static DatasetLimits defaults() {
        return new DatasetLimits(5 * 1024 * 1024, 1000, 64 * 1024, 20);
    }
}
