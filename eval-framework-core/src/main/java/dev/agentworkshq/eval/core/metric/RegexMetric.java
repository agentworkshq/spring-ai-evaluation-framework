package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.NameRules;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class RegexMetric implements Metric {

    public enum Mode {
        MUST_MATCH,
        MUST_NOT_MATCH
    }

    private static final int DEFAULT_MAX_INPUT_CHARS = 50_000;
    private static final Duration DEFAULT_DEADLINE = Duration.ofMillis(200);
    private static final int MAX_PATTERN_CHARS = 500;

    private final String name;
    private final Pattern pattern;
    private final Mode mode;
    private final int maxInputChars;
    private final long deadlineNanos;

    public RegexMetric(String name, String pattern, Mode mode) {
        this(name, pattern, mode, DEFAULT_MAX_INPUT_CHARS, DEFAULT_DEADLINE);
    }

    public RegexMetric(String name, String pattern, Mode mode, int maxInputChars, Duration deadline) {
        if (!NameRules.isValid(name)) {
            throw MetricArgs.bad("metric name is not valid");
        }
        if (pattern == null || pattern.isEmpty() || pattern.length() > MAX_PATTERN_CHARS) {
            throw MetricArgs.bad("pattern must have 1 to " + MAX_PATTERN_CHARS + " characters");
        }
        if (mode == null) {
            throw MetricArgs.bad("mode must not be null");
        }
        if (maxInputChars <= 0) {
            throw MetricArgs.bad("maxInputChars must be positive");
        }
        if (deadline == null || deadline.isZero() || deadline.isNegative()) {
            throw MetricArgs.bad("deadline must be positive");
        }
        try {
            this.pattern = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw MetricArgs.bad("pattern is not a valid regular expression");
        }
        this.name = name;
        this.mode = mode;
        this.maxInputChars = maxInputChars;
        this.deadlineNanos = deadline.toNanos();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
        String answer = output.answer();
        if (answer.length() > maxInputChars) {
            return MetricResult.error(name, "answer is longer than the maximum input length of " + maxInputChars + " characters");
        }
        boolean found;
        try {
            found = pattern.matcher(new DeadlineCharSequence(answer, System.nanoTime() + deadlineNanos)).find();
        } catch (DeadlineExceeded e) {
            return MetricResult.error(name, "regular expression evaluation timed out");
        } catch (StackOverflowError e) {
            return MetricResult.error(name, "regular expression evaluation overflowed the stack");
        }
        boolean passed = mode == Mode.MUST_MATCH ? found : !found;
        String reason = found ? "pattern matched" : "pattern did not match";
        return passed ? MetricResult.passed(name, 1.0, reason) : MetricResult.failed(name, 0.0, reason);
    }

    private static final class DeadlineExceeded extends RuntimeException {

        DeadlineExceeded() {
            super(null, null, false, false);
        }
    }

    private static final class DeadlineCharSequence implements CharSequence {

        private final CharSequence delegate;
        private final long deadline;

        DeadlineCharSequence(CharSequence delegate, long deadline) {
            this.delegate = delegate;
            this.deadline = deadline;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() - deadline > 0) {
                throw new DeadlineExceeded();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new DeadlineCharSequence(delegate.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
