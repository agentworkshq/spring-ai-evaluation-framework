package dev.agentworkshq.eval.core.suite;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.NameRules;
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
        if (values.keySet().stream().anyMatch(k -> k == null)) {
            throw bad(field + " must not contain null names");
        }
        Map<String, Double> sorted = new TreeMap<>(values);
        int position = 0;
        for (Map.Entry<String, Double> e : sorted.entrySet()) {
            position++;
            if (!NameRules.isValid(e.getKey())) {
                throw bad(field + " has an invalid metric name at position " + position);
            }
            if (e.getValue() == null || !(e.getValue() >= 0.0 && e.getValue() <= 1.0)) {
                throw bad(field + " needs values between 0 and 1");
            }
        }
        return Collections.unmodifiableMap(sorted);
    }

    private static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E010, detail);
    }

    @Override
    public String toString() {
        return "Thresholds[minPassRate=" + minPassRate.size() + ", minMeanScore=" + minMeanScore.size()
                + ", maxIncompleteCases=" + maxIncompleteCases + ", maxMetricErrors=" + maxMetricErrors + "]";
    }
}
