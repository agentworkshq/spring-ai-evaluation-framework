package dev.agentworkshq.eval.core.metric;

import static dev.agentworkshq.eval.core.metric.MetricFixtures.answer;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.noAnswerCase;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.withPhrases;
import static dev.agentworkshq.eval.core.metric.MetricFixtures.withSources;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.MetricStatus;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnswerMetricsTest {

    private final AnswerContainsMetric contains = new AnswerContainsMetric();
    private final AnswerNotContainsMetric notContains = new AnswerNotContainsMetric();

    @Test
    void containsPassesWhenAllPhrasesPresent() {
        MetricResult result = contains.evaluate(withPhrases(List.of("alpha", "beta"), null), answer("Beta and ALPHA"));

        assertThat(contains.name()).isEqualTo("answer_contains");
        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.reason()).isEqualTo("2 of 2 required phrases present");
    }

    @Test
    void containsReportsMissingPhraseIndexAndFraction() {
        MetricResult result = contains.evaluate(withPhrases(List.of("a1", "b2", "c3"), null), answer("has a1 and c3"));

        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(result.score()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(result.reason()).isEqualTo("2 of 3 required phrases present, missing phrase #2");
    }

    @Test
    void containsListsSeveralMissingIndexes() {
        MetricResult result = contains.evaluate(withPhrases(List.of("a1", "b2", "c3"), null), answer("a1"));

        assertThat(result.score()).isCloseTo(1.0 / 3.0, within(1e-9));
        assertThat(result.reason()).endsWith("missing phrases #2, #3");
    }

    @Test
    void containsSkipsWithoutPhrases() {
        assertThat(contains.evaluate(withPhrases(null, List.of("x")), answer("x")).status()).isEqualTo(MetricStatus.SKIPPED);
    }

    @Test
    void containsFailsOnEmptyAnswer() {
        MetricResult result = contains.evaluate(withPhrases(List.of("x"), null), answer(""));

        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void containsNormalizesCaseUnicodeAndWhitespace() {
        EvalCaseHolder h = new EvalCaseHolder();

        assertThat(h.passes("hello world", "HELLO   WORLD")).isTrue();
        assertThat(h.passes("hello world", "hello world")).isTrue();
        assertThat(h.passes("hello world", "hello\n\t world")).isTrue();
        assertThat(h.passes("abc", "ａｂｃ")).isTrue();
        assertThat(h.passes("ＡＢ", "ab")).isTrue();
        assertThat(h.passes("café", "café")).isTrue();
        assertThat(h.passes("hello", "hell")).isFalse();
        assertThat(h.passes("a b", "ab")).isFalse();
    }

    @Test
    void notContainsPassesWhenAllAbsent() {
        MetricResult result = notContains.evaluate(withPhrases(null, List.of("bad", "worse")), answer("fine answer"));

        assertThat(notContains.name()).isEqualTo("answer_not_contains");
        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.reason()).isEqualTo("2 of 2 forbidden phrases absent");
    }

    @Test
    void notContainsReportsFoundPhraseIndex() {
        MetricResult result = notContains.evaluate(withPhrases(null, List.of("aa", "bb", "cc", "dd")), answer("AA and  cc"));

        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(result.score()).isEqualTo(0.5);
        assertThat(result.reason()).isEqualTo("2 of 4 forbidden phrases absent, found phrases #1, #3");
    }

    @Test
    void notContainsSkipsWithoutPhrasesAndPassesOnEmptyAnswer() {
        assertThat(notContains.evaluate(withPhrases(List.of("x"), null), answer("x")).status()).isEqualTo(MetricStatus.SKIPPED);
        assertThat(notContains.evaluate(withPhrases(null, List.of("x")), answer("")).status()).isEqualTo(MetricStatus.PASSED);
    }

    @Test
    void regexConstructorValidation() {
        RegexMetric.Mode mode = RegexMetric.Mode.MUST_MATCH;
        assertThatThrownBy(() -> new RegexMetric("bad name", "a", mode)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RegexMetric(null, "a", mode)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RegexMetric("r", null, mode)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RegexMetric("r", "", mode)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RegexMetric("r", "a".repeat(501), mode)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RegexMetric("r", "(", mode)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RegexMetric("r", "a", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RegexMetric("r", "a", mode, 0, Duration.ofMillis(5))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RegexMetric("r", "a", mode, 5, Duration.ZERO)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new RegexMetric("r", "a", mode, 5, null)).isInstanceOf(ValidationException.class);
        assertThat(new RegexMetric("r", "a".repeat(500), mode).name()).isEqualTo("r");
    }

    @Test
    void regexDeadlineIsBetweenOneMillisecondAndTenSeconds() {
        RegexMetric.Mode mode = RegexMetric.Mode.MUST_MATCH;
        for (Duration bad : List.of(Duration.ZERO, Duration.ofNanos(999_999), Duration.ofSeconds(10).plusNanos(1),
                Duration.ofMillis(Long.MAX_VALUE), Duration.ofNanos(Long.MAX_VALUE),
                Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> new RegexMetric("r", "a", mode, 5, bad)).isInstanceOf(ValidationException.class)
                    .hasMessageContaining("between 1 millisecond and 10 seconds");
        }
        assertThat(new RegexMetric("r", "a", mode, 5, Duration.ofMillis(1)).name()).isEqualTo("r");
        assertThat(new RegexMetric("r", "a", mode, 5, Duration.ofSeconds(10)).evaluate(withSources(), answer("a"))
                .status()).isEqualTo(MetricStatus.PASSED);
    }

    @Test
    void regexMustMatch() {
        RegexMetric metric = new RegexMetric("has_digits", "\\d{3}", RegexMetric.Mode.MUST_MATCH);

        MetricResult passed = metric.evaluate(withSources(), answer("call 555 now"));
        MetricResult failed = metric.evaluate(withSources(), answer("call me"));

        assertThat(passed.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(passed.score()).isEqualTo(1.0);
        assertThat(failed.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(failed.score()).isEqualTo(0.0);
    }

    @Test
    void regexMustNotMatch() {
        RegexMetric metric = new RegexMetric("no_digits", "\\d", RegexMetric.Mode.MUST_NOT_MATCH);

        assertThat(metric.evaluate(withSources(), answer("none here")).status()).isEqualTo(MetricStatus.PASSED);
        MetricResult failed = metric.evaluate(withSources(), answer("has 1"));
        assertThat(failed.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(failed.score()).isEqualTo(0.0);
    }

    @Test
    void catastrophicPatternGivesErrorWithinBoundedTime() {
        RegexMetric metric = new RegexMetric("evil", "((a+)+)+c", RegexMetric.Mode.MUST_NOT_MATCH, 50_000, Duration.ofMillis(200));
        TargetOutput output = answer("a".repeat(40) + "b");

        MetricResult result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> metric.evaluate(withSources(), output));

        assertThat(result.status()).isEqualTo(MetricStatus.ERROR);
        assertThat(result.score()).isNull();
        assertThat(result.reason()).contains("timed out");
    }

    @Test
    void classicNestedQuantifierPatternReturnsInBoundedTime() {
        RegexMetric metric = new RegexMetric("evil", "(a+)+$", RegexMetric.Mode.MUST_NOT_MATCH, 50_000, Duration.ofMillis(200));
        TargetOutput output = answer("a".repeat(5000) + "b");

        MetricResult result = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> metric.evaluate(withSources(), output));

        assertThat(result).isNotNull();
        assertThat(result.status()).isIn(MetricStatus.PASSED, MetricStatus.ERROR);
    }

    @Test
    void inputOverLimitGivesErrorWithDifferentReason() {
        RegexMetric metric = new RegexMetric("r", "a", RegexMetric.Mode.MUST_MATCH, 10, Duration.ofMillis(200));

        MetricResult result = metric.evaluate(withSources(), answer("a".repeat(11)));

        assertThat(result.status()).isEqualTo(MetricStatus.ERROR);
        assertThat(result.reason()).contains("longer than").doesNotContain("timed out");
        assertThat(metric.evaluate(withSources(), answer("a".repeat(10))).status()).isEqualTo(MetricStatus.PASSED);
    }

    @Test
    void noAnswerPassesBothWays() {
        NoAnswerMetric metric = new NoAnswerMetric();

        MetricResult answered = metric.evaluate(withSources(), output(true));
        MetricResult abstained = metric.evaluate(noAnswerCase(), output(false));

        assertThat(metric.name()).isEqualTo("no_answer");
        assertThat(answered.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(answered.score()).isEqualTo(1.0);
        assertThat(abstained.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(abstained.reason()).isNotEqualTo(answered.reason());
    }

    @Test
    void noAnswerFailureDirectionsHaveDifferentReasons() {
        NoAnswerMetric metric = new NoAnswerMetric();

        MetricResult shouldHaveAbstained = metric.evaluate(noAnswerCase(), output(true));
        MetricResult shouldHaveAnswered = metric.evaluate(withSources(), output(false));

        assertThat(shouldHaveAbstained.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(shouldHaveAbstained.score()).isEqualTo(0.0);
        assertThat(shouldHaveAbstained.reason()).isEqualTo("answered but should have abstained");
        assertThat(shouldHaveAnswered.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(shouldHaveAnswered.reason()).isEqualTo("abstained but should have answered");
    }

    private static TargetOutput output(boolean answered) {
        return new TargetOutput("", answered, List.of(), List.of(), Duration.ZERO, Map.of());
    }

    private final class EvalCaseHolder {

        boolean passes(String phrase, String text) {
            return contains.evaluate(withPhrases(List.of(phrase), null), answer(text)).status() == MetricStatus.PASSED;
        }
    }
}
