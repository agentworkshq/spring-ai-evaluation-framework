package dev.agentworkshq.eval.core.runner;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.NameRules;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record RunConfig(
        String runId,
        int maxCases,
        Set<String> includeTags,
        int concurrency,
        Duration perCaseTimeout,
        Duration perMetricTimeout,
        Duration totalTimeout,
        int maxAttempts,
        Duration retryDelay,
        Integer maxAbandonedCalls,
        int maxJudgeCalls,
        Map<String, String> labels) {

    private static final Duration SHORTEST = Duration.ofMillis(1);
    private static final Duration LONGEST = Duration.ofDays(365);

    public RunConfig {
        if (runId != null && !NameRules.isValid(runId)) {
            throw bad("runId is not a valid name");
        }
        if (maxCases < 1 || maxCases > 100_000) {
            throw bad("maxCases must be between 1 and 100000");
        }
        includeTags = tags(includeTags);
        if (concurrency < 1 || concurrency > 16) {
            throw bad("concurrency must be between 1 and 16");
        }
        timeout(perCaseTimeout, "perCaseTimeout");
        timeout(perMetricTimeout, "perMetricTimeout");
        timeout(totalTimeout, "totalTimeout");
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw bad("maxAttempts must be between 1 and 5");
        }
        if (retryDelay == null || retryDelay.isNegative() || retryDelay.compareTo(LONGEST) > 0) {
            throw bad("retryDelay must not be negative or longer than 365 days");
        }
        if (maxAbandonedCalls != null && maxAbandonedCalls < 1) {
            throw bad("maxAbandonedCalls must be at least 1");
        }
        if (maxJudgeCalls < 0 || maxJudgeCalls > 100_000) {
            throw bad("maxJudgeCalls must be between 0 and 100000");
        }
        labels = labels(labels);
    }

    public static RunConfig defaults() {
        return new RunConfig(null, 1000, Set.of(), 1, Duration.ofSeconds(60), Duration.ofSeconds(60),
                Duration.ofMinutes(30), 1, Duration.ZERO, null, 500, Map.of());
    }

    public int effectiveMaxAbandonedCalls() {
        return maxAbandonedCalls == null ? concurrency : maxAbandonedCalls;
    }

    public RunConfig withRunId(String value) {
        return new RunConfig(value, maxCases, includeTags, concurrency, perCaseTimeout, perMetricTimeout, totalTimeout,
                maxAttempts, retryDelay, maxAbandonedCalls, maxJudgeCalls, labels);
    }

    public RunConfig withMaxCases(int value) {
        return new RunConfig(runId, value, includeTags, concurrency, perCaseTimeout, perMetricTimeout, totalTimeout,
                maxAttempts, retryDelay, maxAbandonedCalls, maxJudgeCalls, labels);
    }

    public RunConfig withIncludeTags(Set<String> value) {
        return new RunConfig(runId, maxCases, value, concurrency, perCaseTimeout, perMetricTimeout, totalTimeout,
                maxAttempts, retryDelay, maxAbandonedCalls, maxJudgeCalls, labels);
    }

    public RunConfig withConcurrency(int value) {
        return new RunConfig(runId, maxCases, includeTags, value, perCaseTimeout, perMetricTimeout, totalTimeout,
                maxAttempts, retryDelay, maxAbandonedCalls, maxJudgeCalls, labels);
    }

    public RunConfig withPerCaseTimeout(Duration value) {
        return new RunConfig(runId, maxCases, includeTags, concurrency, value, perMetricTimeout, totalTimeout,
                maxAttempts, retryDelay, maxAbandonedCalls, maxJudgeCalls, labels);
    }

    public RunConfig withPerMetricTimeout(Duration value) {
        return new RunConfig(runId, maxCases, includeTags, concurrency, perCaseTimeout, value, totalTimeout,
                maxAttempts, retryDelay, maxAbandonedCalls, maxJudgeCalls, labels);
    }

    public RunConfig withTotalTimeout(Duration value) {
        return new RunConfig(runId, maxCases, includeTags, concurrency, perCaseTimeout, perMetricTimeout, value,
                maxAttempts, retryDelay, maxAbandonedCalls, maxJudgeCalls, labels);
    }

    public RunConfig withMaxAttempts(int value) {
        return new RunConfig(runId, maxCases, includeTags, concurrency, perCaseTimeout, perMetricTimeout, totalTimeout,
                value, retryDelay, maxAbandonedCalls, maxJudgeCalls, labels);
    }

    public RunConfig withRetryDelay(Duration value) {
        return new RunConfig(runId, maxCases, includeTags, concurrency, perCaseTimeout, perMetricTimeout, totalTimeout,
                maxAttempts, value, maxAbandonedCalls, maxJudgeCalls, labels);
    }

    public RunConfig withMaxAbandonedCalls(Integer value) {
        return new RunConfig(runId, maxCases, includeTags, concurrency, perCaseTimeout, perMetricTimeout, totalTimeout,
                maxAttempts, retryDelay, value, maxJudgeCalls, labels);
    }

    public RunConfig withMaxJudgeCalls(int value) {
        return new RunConfig(runId, maxCases, includeTags, concurrency, perCaseTimeout, perMetricTimeout, totalTimeout,
                maxAttempts, retryDelay, maxAbandonedCalls, value, labels);
    }

    public RunConfig withLabels(Map<String, String> value) {
        return new RunConfig(runId, maxCases, includeTags, concurrency, perCaseTimeout, perMetricTimeout, totalTimeout,
                maxAttempts, retryDelay, maxAbandonedCalls, maxJudgeCalls, value);
    }

    private static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E011, detail);
    }

    private static void timeout(Duration value, String field) {
        if (value == null || value.compareTo(SHORTEST) < 0 || value.compareTo(LONGEST) > 0) {
            throw bad(field + " must be at least 1 millisecond and at most 365 days");
        }
    }

    private static Set<String> tags(Set<String> values) {
        if (values == null) {
            return Collections.emptySortedSet();
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String tag : values) {
            if (tag == null || tag.isBlank() || tag.length() > 50) {
                throw bad("includeTags entries must not be blank or longer than 50 characters");
            }
            sorted.add(tag);
        }
        return Collections.unmodifiableSortedSet(sorted);
    }

    private static Map<String, String> labels(Map<String, String> values) {
        if (values == null) {
            return Map.of();
        }
        if (values.size() > 20) {
            throw bad("labels has more than 20 entries");
        }
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank() || key.length() > 200) {
                throw bad("label keys must not be blank or longer than 200 characters");
            }
            if (e.getValue() == null || e.getValue().length() > 200) {
                throw bad("label values must not be null or longer than 200 characters");
            }
            EvalRun.requireSafeConfigKey("label." + key);
            sorted.put(key, e.getValue());
        }
        return Collections.unmodifiableMap(sorted);
    }

    @Override
    public String toString() {
        return "RunConfig[runId=" + runId + ", maxCases=" + maxCases + ", includeTags=" + includeTags.size()
                + ", concurrency=" + concurrency + ", perCaseTimeout=" + perCaseTimeout + ", perMetricTimeout="
                + perMetricTimeout + ", totalTimeout=" + totalTimeout + ", maxAttempts=" + maxAttempts + ", retryDelay="
                + retryDelay + ", maxAbandonedCalls=" + maxAbandonedCalls + ", maxJudgeCalls=" + maxJudgeCalls
                + ", labels=" + labels.size() + "]";
    }
}
