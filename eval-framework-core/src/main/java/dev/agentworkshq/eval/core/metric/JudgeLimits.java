package dev.agentworkshq.eval.core.metric;

public record JudgeLimits(int question, int answer, int context, int expectedAnswer) {

    public JudgeLimits {
        for (int limit : new int[] {question, answer, context, expectedAnswer}) {
            if (limit < 1 || limit > 1_000_000) {
                throw MetricArgs.bad("judge section limits must be between 1 and 1000000 characters");
            }
        }
    }

    public static JudgeLimits defaults() {
        return new JudgeLimits(2000, 6000, 12000, 4000);
    }
}
