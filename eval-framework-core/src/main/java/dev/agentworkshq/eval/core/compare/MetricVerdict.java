package dev.agentworkshq.eval.core.compare;

public enum MetricVerdict {
    UNCHANGED,
    IMPROVED,
    REGRESSED,
    NOT_COMPARABLE,
    MISSING_IN_CANDIDATE,
    NEW_IN_CANDIDATE
}
