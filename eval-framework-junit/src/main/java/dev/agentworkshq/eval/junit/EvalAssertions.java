package dev.agentworkshq.eval.junit;

import dev.agentworkshq.eval.core.compare.CaseChange;
import dev.agentworkshq.eval.core.compare.ChangeKind;
import dev.agentworkshq.eval.core.compare.MetricAggregate;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.Text;
import dev.agentworkshq.eval.core.suite.EvalOutcome;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.opentest4j.AssertionFailedError;

public final class EvalAssertions {

    static final int MAX_MESSAGE_CHARS = 20000;

    private static final int MAX_LISTED = 10;

    private EvalAssertions() {
    }

    public static void assertGate(EvalOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (!outcome.passesGate()) {
            throw new AssertionFailedError(Text.truncate(outcome.consoleReport(), MAX_MESSAGE_CHARS));
        }
    }

    public static OutcomeAssert assertThat(EvalOutcome outcome) {
        return new OutcomeAssert(Objects.requireNonNull(outcome, "outcome"));
    }

    public static final class OutcomeAssert {

        private final EvalOutcome outcome;

        private OutcomeAssert(EvalOutcome outcome) {
            this.outcome = outcome;
        }

        public OutcomeAssert passesGate() {
            if (!outcome.passesGate()) {
                fail("the gate failed with " + outcome.violations().size() + " violations: "
                        + String.join("; ", outcome.violations().subList(0, Math.min(outcome.violations().size(), MAX_LISTED))));
            }
            return this;
        }

        public OutcomeAssert metricPassRateAtLeast(String metricName, double minimum) {
            MetricAggregate a = aggregate(metricName);
            check("pass rate", metricName, a == null ? null : a.passRate(), minimum);
            return this;
        }

        public OutcomeAssert metricMeanScoreAtLeast(String metricName, double minimum) {
            MetricAggregate a = aggregate(metricName);
            check("mean score", metricName, a == null ? null : a.meanScore(), minimum);
            return this;
        }

        public OutcomeAssert noCaseRegressions() {
            if (outcome.comparison() == null) {
                fail("there is no baseline comparison to check for regressions");
            }
            List<String> ids = outcome.comparison().caseChanges().stream()
                    .filter(c -> c.kind() == ChangeKind.REGRESSION).map(CaseChange::caseId).distinct().toList();
            if (!ids.isEmpty()) {
                fail(ids.size() + " cases regressed: " + listed(ids));
            }
            return this;
        }

        public OutcomeAssert noMetricErrors() {
            List<MetricAggregate> failing = outcome.summary().metrics().stream().filter(a -> a.error() > 0).toList();
            if (!failing.isEmpty()) {
                int total = failing.stream().mapToInt(MetricAggregate::error).sum();
                fail(total + " metric errors in metrics " + listed(failing.stream().map(MetricAggregate::metricName).toList()));
            }
            return this;
        }

        public OutcomeAssert allCasesCompleted() {
            int incomplete = 0;
            for (Map.Entry<CaseStatus, Integer> e : outcome.summary().caseCounts().entrySet()) {
                if (e.getKey() != CaseStatus.COMPLETED) {
                    incomplete += e.getValue();
                }
            }
            if (incomplete > 0) {
                fail(incomplete + " cases did not complete");
            }
            return this;
        }

        private MetricAggregate aggregate(String metricName) {
            return outcome.summary().metrics().stream().filter(a -> a.metricName().equals(metricName)).findFirst().orElse(null);
        }

        private void check(String what, String metricName, Double actual, double minimum) {
            if (actual == null) {
                fail("metric " + metricName + " has no " + what + ", expected at least " + num(minimum));
            } else if (actual < minimum) {
                fail(what + " of metric " + metricName + " is " + num(actual) + ", expected at least " + num(minimum));
            }
        }

        private static String listed(List<String> values) {
            String text = String.join(", ", values.subList(0, Math.min(values.size(), MAX_LISTED)));
            return values.size() > MAX_LISTED ? text + " and " + (values.size() - MAX_LISTED) + " more" : text;
        }

        private static String num(double value) {
            return String.format(Locale.ROOT, "%.4f", value);
        }

        private void fail(String message) {
            throw new AssertionFailedError("Run " + outcome.run().runId() + ": " + message);
        }
    }
}
