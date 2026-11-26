package dev.agentworkshq.eval.core.compare;

public record ConfigChange(String key, String baselineValue, String candidateValue) {

    @Override
    public String toString() {
        return "ConfigChange[inBaseline=" + (baselineValue != null) + ", inCandidate=" + (candidateValue != null) + "]";
    }
}
