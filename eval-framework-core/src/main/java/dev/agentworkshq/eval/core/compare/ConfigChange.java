package dev.agentworkshq.eval.core.compare;

public record ConfigChange(String key, String baselineValue, String candidateValue) {
}
