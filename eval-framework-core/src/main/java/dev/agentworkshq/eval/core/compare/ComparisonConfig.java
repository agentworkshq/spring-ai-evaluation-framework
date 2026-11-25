package dev.agentworkshq.eval.core.compare;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record ComparisonConfig(
        double defaultTolerance,
        Map<String, Double> metricTolerances,
        int maxCaseRegressions,
        boolean requireSameDataset,
        boolean failOnIncomparable,
        boolean failOnLostCoverage,
        Map<String, Double> minPassRates) {

    public ComparisonConfig {
        fraction(defaultTolerance, "defaultTolerance");
        metricTolerances = fractions(metricTolerances, "metricTolerances");
        if (maxCaseRegressions < 0) {
            throw bad("maxCaseRegressions must not be negative");
        }
        minPassRates = fractions(minPassRates, "minPassRates");
    }

    public static ComparisonConfig defaults() {
        return new ComparisonConfig(0.0, Map.of(), 0, true, true, true, Map.of());
    }

    public double toleranceFor(String metricName) {
        return metricTolerances.getOrDefault(metricName, defaultTolerance);
    }

    public ComparisonConfig withDefaultTolerance(double value) {
        return new ComparisonConfig(value, metricTolerances, maxCaseRegressions, requireSameDataset, failOnIncomparable,
                failOnLostCoverage, minPassRates);
    }

    public ComparisonConfig withMetricTolerance(String metricName, double value) {
        Map<String, Double> copy = new TreeMap<>(metricTolerances);
        copy.put(metricName, value);
        return new ComparisonConfig(defaultTolerance, copy, maxCaseRegressions, requireSameDataset, failOnIncomparable,
                failOnLostCoverage, minPassRates);
    }

    public ComparisonConfig withMaxCaseRegressions(int value) {
        return new ComparisonConfig(defaultTolerance, metricTolerances, value, requireSameDataset, failOnIncomparable,
                failOnLostCoverage, minPassRates);
    }

    public ComparisonConfig withRequireSameDataset(boolean value) {
        return new ComparisonConfig(defaultTolerance, metricTolerances, maxCaseRegressions, value, failOnIncomparable,
                failOnLostCoverage, minPassRates);
    }

    public ComparisonConfig withFailOnIncomparable(boolean value) {
        return new ComparisonConfig(defaultTolerance, metricTolerances, maxCaseRegressions, requireSameDataset, value,
                failOnLostCoverage, minPassRates);
    }

    public ComparisonConfig withFailOnLostCoverage(boolean value) {
        return new ComparisonConfig(defaultTolerance, metricTolerances, maxCaseRegressions, requireSameDataset,
                failOnIncomparable, value, minPassRates);
    }

    public ComparisonConfig withMinPassRate(String metricName, double value) {
        Map<String, Double> copy = new TreeMap<>(minPassRates);
        copy.put(metricName, value);
        return new ComparisonConfig(defaultTolerance, metricTolerances, maxCaseRegressions, requireSameDataset,
                failOnIncomparable, failOnLostCoverage, copy);
    }

    private static void fraction(double value, String field) {
        if (!(value >= 0.0 && value <= 1.0)) {
            throw bad(field + " must be between 0 and 1");
        }
    }

    private static Map<String, Double> fractions(Map<String, Double> values, String field) {
        if (values == null) {
            return Map.of();
        }
        for (Map.Entry<String, Double> e : values.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                throw bad(field + " must not contain blank names or null values");
            }
            fraction(e.getValue(), field);
        }
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    private static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E010, detail);
    }
}
