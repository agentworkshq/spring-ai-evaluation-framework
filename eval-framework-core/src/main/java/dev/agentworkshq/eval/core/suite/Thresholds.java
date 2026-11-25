package dev.agentworkshq.eval.core.suite;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record Thresholds(
        Map<String, Double> minPassRate,
        Map<String, Double> minMeanScore,
        int maxIncompleteCases,
        int maxMetricErrors) {

    public Thresholds {
        minPassRate = fractions(minPassRate, "minPassRate");
        minMeanScore = fractions(minMeanScore, "minMeanScore");
        if (maxIncompleteCases < 0 || maxMetricErrors < 0) {
            throw bad("maxIncompleteCases and maxMetricErrors must not be negative");
        }
    }

    public static Thresholds defaults() {
        return new Thresholds(Map.of(), Map.of(), 0, 0);
    }

    public Thresholds withMinPassRate(String metricName, double value) {
        Map<String, Double> copy = new TreeMap<>(minPassRate);
        copy.put(metricName, value);
        return new Thresholds(copy, minMeanScore, maxIncompleteCases, maxMetricErrors);
    }

    public Thresholds withMinMeanScore(String metricName, double value) {
        Map<String, Double> copy = new TreeMap<>(minMeanScore);
        copy.put(metricName, value);
        return new Thresholds(minPassRate, copy, maxIncompleteCases, maxMetricErrors);
    }

    public Thresholds withMaxIncompleteCases(int value) {
        return new Thresholds(minPassRate, minMeanScore, value, maxMetricErrors);
    }

    public Thresholds withMaxMetricErrors(int value) {
        return new Thresholds(minPassRate, minMeanScore, maxIncompleteCases, value);
    }

    private static Map<String, Double> fractions(Map<String, Double> values, String field) {
        if (values == null) {
            return Map.of();
        }
        for (Map.Entry<String, Double> e : values.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null
                    || !(e.getValue() >= 0.0 && e.getValue() <= 1.0)) {
                throw bad(field + " needs metric names and values between 0 and 1");
            }
        }
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    private static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E010, detail);
    }
}
