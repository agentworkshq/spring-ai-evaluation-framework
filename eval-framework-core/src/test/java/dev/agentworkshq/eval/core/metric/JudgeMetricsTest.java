package dev.agentworkshq.eval.core.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.judge.RunContext;
import dev.agentworkshq.eval.core.judge.ScriptedJudgeModel;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.MetricStatus;
import dev.agentworkshq.eval.core.model.NameRules;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class JudgeMetricsTest {

    private static final String SCORE_4 = "{\"score\":4}";

    private static EvalCase evalCase(String expectedAnswer, boolean noAnswer) {
        return new EvalCase("c1", "What is the refund window?", expectedAnswer, null, noAnswer, null, null, null, null);
    }

    private static TargetOutput output(String answer, boolean answered, String... chunkTexts) {
        List<RetrievedChunk> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < chunkTexts.length; i++) {
            chunks.add(new RetrievedChunk("k" + i, "doc" + i, "doc" + i + ".md", null, chunkTexts[i], null));
        }
        return new TargetOutput(answer, answered, chunks, null, Duration.ZERO, null);
    }

    private static TargetOutput good() {
        return output("Thirty days.", true, "Refunds are accepted for thirty days.");
    }

    private static List<Function<ScriptedJudgeModel, Metric>> allMetrics() {
        return List.of(FaithfulnessMetric::new, AnswerRelevancyMetric::new, CorrectnessMetric::new);
    }

    @Test
    void passesWithExactScoreAndReason() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", "{\"score\": 5}");

        MetricResult result = new FaithfulnessMetric(judge).evaluate(evalCase(null, false), good(), RunContext.of(10));

        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.reason()).isEqualTo("judge scores 5 mean 5.00 pass score 4");
        assertThat(result.metricName()).isEqualTo("faithfulness");
    }

    @Test
    void failsBelowThePassScoreWithMappedScore() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", "{\"score\":2}");

        MetricResult result = new AnswerRelevancyMetric(judge).evaluate(evalCase(null, false), good(), RunContext.of(10));

        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(result.score()).isEqualTo(0.25);
        assertThat(result.reason()).isEqualTo("judge scores 2 mean 2.00 pass score 4");
    }

    @Test
    void scoresMapFromOneToFiveOntoZeroToOne() {
        double[] expected = {0.0, 0.25, 0.5, 0.75, 1.0};
        for (int s = 1; s <= 5; s++) {
            ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", "{\"score\":" + s + "}");
            MetricResult result = new CorrectnessMetric(judge).evaluate(evalCase("Thirty days.", false), good(), RunContext.of(1));
            assertThat(result.score()).isEqualTo(expected[s - 1]);
        }
    }

    @Test
    void repeatsAverageTheScores() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", "{\"score\":4}", "{\"score\":4}", "{\"score\":5}");
        RunContext context = RunContext.of(10);

        MetricResult result = new FaithfulnessMetric(judge, 3, 4).evaluate(evalCase(null, false), good(), context);

        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.score()).isCloseTo((13.0 / 3 - 1) / 4, within(1e-12));
        assertThat(result.reason()).isEqualTo("judge scores 4,4,5 mean 4.33 pass score 4");
        assertThat(judge.calls()).isEqualTo(3);
        assertThat(context.budget().used()).isEqualTo(3);
    }

    @Test
    void meanExactlyAtThePassScorePasses() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", "{\"score\":3}", "{\"score\":5}");

        MetricResult result = new AnswerRelevancyMetric(judge, 2, 4).evaluate(evalCase(null, false), good(), RunContext.of(2));

        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.score()).isEqualTo(0.75);
    }

    @Test
    void skipsWithoutSpendingBudgetOrCallingTheJudge() {
        for (Function<ScriptedJudgeModel, Metric> factory : List.of(
                (Function<ScriptedJudgeModel, Metric>) FaithfulnessMetric::new, AnswerRelevancyMetric::new)) {
            ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
            Metric metric = factory.apply(judge);
            RunContext context = RunContext.of(10);

            assertThat(metric.evaluate(evalCase(null, true), good(), context).status()).isEqualTo(MetricStatus.SKIPPED);
            assertThat(metric.evaluate(evalCase(null, false), output("x", false, "c"), context).status())
                    .isEqualTo(MetricStatus.SKIPPED);
            assertThat(metric.evaluate(evalCase(null, false), output("  ", true, "c"), context).status())
                    .isEqualTo(MetricStatus.SKIPPED);
            assertThat(judge.calls()).isZero();
            assertThat(context.budget().used()).isZero();
        }
    }

    @Test
    void correctnessSkipsWithoutExpectedAnswerOrWhenNoAnswerIsExpected() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
        CorrectnessMetric metric = new CorrectnessMetric(judge);
        RunContext context = RunContext.of(10);

        assertThat(metric.evaluate(evalCase(null, false), good(), context).status()).isEqualTo(MetricStatus.SKIPPED);
        assertThat(metric.evaluate(evalCase(" ", false), good(), context).status()).isEqualTo(MetricStatus.SKIPPED);
        assertThat(metric.evaluate(evalCase(null, true), good(), context).status()).isEqualTo(MetricStatus.SKIPPED);
        assertThat(judge.calls()).isZero();
        assertThat(context.budget().used()).isZero();
    }

    @Test
    void faithfulnessFailsWithoutContextAndWithoutCallingTheJudge() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
        FaithfulnessMetric metric = new FaithfulnessMetric(judge);
        RunContext context = RunContext.of(10);

        MetricResult none = metric.evaluate(evalCase(null, false), output("Thirty days.", true), context);
        MetricResult blank = metric.evaluate(evalCase(null, false), output("Thirty days.", true, "  "), context);

        for (MetricResult result : List.of(none, blank)) {
            assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
            assertThat(result.score()).isEqualTo(0.0);
            assertThat(result.reason()).isEqualTo("no retrieved context");
        }
        assertThat(judge.calls()).isZero();
        assertThat(context.budget().used()).isZero();
    }

    @Test
    void sectionsMatchWhatEachMetricNeeds() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
        RunContext context = RunContext.of(10);
        EvalCase c = evalCase("Thirty days.", false);

        new FaithfulnessMetric(judge).evaluate(c, good(), context);
        new AnswerRelevancyMetric(judge).evaluate(c, good(), context);
        new CorrectnessMetric(judge).evaluate(c, good(), context);

        List<String> prompts = judge.userPrompts();
        assertThat(prompts.get(0)).contains(" CONTEXT>>>").contains(" ANSWER>>>").doesNotContain(" QUESTION>>>")
                .contains("[1] Refunds are accepted for thirty days.");
        assertThat(prompts.get(1)).contains(" QUESTION>>>").contains(" ANSWER>>>").doesNotContain(" CONTEXT>>>");
        assertThat(prompts.get(2)).contains(" QUESTION>>>").contains(" EXPECTED_ANSWER>>>").contains(" ANSWER>>>")
                .doesNotContain(" CONTEXT>>>");
    }

    @Test
    void judgeExceptionGivesErrorWithOnlyTheClassName() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", new IllegalStateException("secret detail"));

        MetricResult result = new FaithfulnessMetric(judge).evaluate(evalCase(null, false), good(), RunContext.of(5));

        assertThat(result.status()).isEqualTo(MetricStatus.ERROR);
        assertThat(result.score()).isNull();
        assertThat(result.reason()).isEqualTo("IllegalStateException");
    }

    @Test
    void rejectedRepliesGiveError() {
        for (String reply : List.of("not json", "{\"score\":9}", "{\"score\":4} extra", "```\n```", "{\"score\":4.5}")) {
            ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", reply);

            MetricResult result = new AnswerRelevancyMetric(judge).evaluate(evalCase(null, false), good(), RunContext.of(5));

            assertThat(result.status()).isEqualTo(MetricStatus.ERROR);
            assertThat(result.reason()).isEqualTo("invalid judge reply");
        }
    }

    @Test
    void midSequenceFailureGivesErrorNotAPartialResult() {
        ScriptedJudgeModel throwing = new ScriptedJudgeModel("judge-a", "{\"score\":5}", "{\"score\":5}",
                new UnsupportedOperationException("x"));
        ScriptedJudgeModel invalid = new ScriptedJudgeModel("judge-a", "{\"score\":5}", "oops", "{\"score\":5}");

        MetricResult first = new FaithfulnessMetric(throwing, 3, 4).evaluate(evalCase(null, false), good(), RunContext.of(5));
        MetricResult second = new FaithfulnessMetric(invalid, 3, 4).evaluate(evalCase(null, false), good(), RunContext.of(5));

        assertThat(first.status()).isEqualTo(MetricStatus.ERROR);
        assertThat(first.score()).isNull();
        assertThat(first.reason()).isEqualTo("UnsupportedOperationException");
        assertThat(throwing.calls()).isEqualTo(3);
        assertThat(second.status()).isEqualTo(MetricStatus.ERROR);
        assertThat(second.reason()).isEqualTo("invalid judge reply");
        assertThat(invalid.calls()).isEqualTo(2);
    }

    @Test
    void exhaustedBudgetGivesErrorWithoutCallingTheJudge() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
        EvalCase c = evalCase(null, false);

        MetricResult noBudget = new FaithfulnessMetric(judge).evaluate(c, good(), RunContext.none());
        MetricResult twoArguments = new FaithfulnessMetric(judge).evaluate(c, good());

        for (MetricResult result : List.of(noBudget, twoArguments)) {
            assertThat(result.status()).isEqualTo(MetricStatus.ERROR);
            assertThat(result.reason()).isEqualTo("judge call budget exhausted");
        }
        assertThat(judge.calls()).isZero();
    }

    @Test
    void repeatsAboveTheRemainingBudgetSpendNothing() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
        RunContext context = RunContext.of(2);
        EvalCase c = evalCase(null, false);

        MetricResult tooMany = new FaithfulnessMetric(judge, 3, 4).evaluate(c, good(), context);
        MetricResult fits = new FaithfulnessMetric(judge, 2, 4).evaluate(c, good(), context);

        assertThat(tooMany.status()).isEqualTo(MetricStatus.ERROR);
        assertThat(tooMany.reason()).isEqualTo("judge call budget exhausted");
        assertThat(fits.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(judge.calls()).isEqualTo(2);
        assertThat(context.budget().used()).isEqualTo(2);
    }

    @Test
    void reportsTruncationAndSendsOnlyTheLimit() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
        JudgeLimits limits = new JudgeLimits(100, 5, 100, 100);

        MetricResult result = new AnswerRelevancyMetric(judge, 1, 4, limits)
                .evaluate(evalCase(null, false), output("abcdefghij", true), RunContext.of(1));

        assertThat(result.reason()).isEqualTo("judge scores 4 mean 4.00 pass score 4, input truncated");
        assertThat(judge.userPrompts().get(0)).contains("\nabcde\n").doesNotContain("abcdef");
    }

    @Test
    void contextIsLimitedInTotal() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
        JudgeLimits limits = new JudgeLimits(100, 100, 20, 100);

        MetricResult result = new FaithfulnessMetric(judge, 1, 4, limits).evaluate(evalCase(null, false),
                output("a", true, "x".repeat(30), "y".repeat(30)), RunContext.of(1));

        assertThat(result.reason()).endsWith("input truncated");
        assertThat(judge.userPrompts().get(0)).doesNotContain("x".repeat(21)).doesNotContain("y");
    }

    @Test
    void systemPromptIsAConstantPerMetric() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
        FaithfulnessMetric metric = new FaithfulnessMetric(judge);

        metric.evaluate(evalCase(null, false), good(), RunContext.of(5));
        metric.evaluate(evalCase(null, false), output("Other answer.", true, "other chunk"), RunContext.of(5));

        assertThat(judge.systemPrompts().get(0)).isEqualTo(judge.systemPrompts().get(1)).contains("untrusted data");
    }

    @Test
    void runConfigListsNonSecretSettings() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);

        assertThat(new FaithfulnessMetric(judge, 3, 5).runConfig()).isEqualTo(Map.of("judge", "judge-a", "repeats", "3",
                "passScore", "5", "promptVersion", "faithfulness-v1"));
        assertThat(new AnswerRelevancyMetric(judge).runConfig()).containsEntry("promptVersion", "answer-relevancy-v1")
                .containsEntry("repeats", "1").containsEntry("passScore", "4");
        assertThat(new CorrectnessMetric(judge).runConfig()).containsEntry("promptVersion", "correctness-v1");
    }

    @Test
    void namesAreValidForResults() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);

        assertThat(List.of(new FaithfulnessMetric(judge).name(), new AnswerRelevancyMetric(judge).name(),
                new CorrectnessMetric(judge).name())).containsExactly("faithfulness", "answer_relevancy", "correctness")
                .allMatch(NameRules::isValid);
    }

    @Test
    void validatesConstructorArguments() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
        ScriptedJudgeModel secretName = new ScriptedJudgeModel("api-key", SCORE_4);
        ScriptedJudgeModel blankName = new ScriptedJudgeModel(" ", SCORE_4);

        for (Function<ScriptedJudgeModel, Metric> factory : allMetrics()) {
            assertThatThrownBy(() -> factory.apply(secretName)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> factory.apply(blankName)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> factory.apply(null)).isInstanceOf(ValidationException.class);
        }
        assertThatThrownBy(() -> new FaithfulnessMetric(judge, 0, 4)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new FaithfulnessMetric(judge, 6, 4)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new AnswerRelevancyMetric(judge, 1, 0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CorrectnessMetric(judge, 1, 6)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CorrectnessMetric(judge, 1, 4, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new JudgeLimits(0, 1, 1, 1)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new JudgeLimits(1, 1, 1, 1_000_001)).isInstanceOf(ValidationException.class);
        assertThat(new FaithfulnessMetric(judge, 5, 1)).isNotNull();
        assertThat(JudgeLimits.defaults()).isEqualTo(new JudgeLimits(2000, 6000, 12000, 4000));
    }

    @Test
    void sameInputAndRepliesGiveEqualResults() {
        MetricResult first = new FaithfulnessMetric(new ScriptedJudgeModel("j", "{\"score\":4}", "{\"score\":3}"), 2, 3)
                .evaluate(evalCase(null, false), good(), RunContext.of(5));
        MetricResult second = new FaithfulnessMetric(new ScriptedJudgeModel("j", "{\"score\":4}", "{\"score\":3}"), 2, 3)
                .evaluate(evalCase(null, false), good(), RunContext.of(5));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void stopsWhenTheThreadIsInterrupted() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-a", SCORE_4);
        Thread.currentThread().interrupt();
        MetricResult result;
        try {
            result = new FaithfulnessMetric(judge, 3, 4).evaluate(evalCase(null, false), good(), RunContext.of(5));
        } finally {
            Thread.interrupted();
        }

        assertThat(result.status()).isEqualTo(MetricStatus.ERROR);
        assertThat(judge.calls()).isZero();
    }
}
