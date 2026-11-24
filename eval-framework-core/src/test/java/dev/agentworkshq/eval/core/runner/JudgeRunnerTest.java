package dev.agentworkshq.eval.core.runner;

import static dev.agentworkshq.eval.core.runner.RunnerFixtures.MARKER;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.dataset;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.fast;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.metric;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.runner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.judge.ScriptedJudgeModel;
import dev.agentworkshq.eval.core.metric.AnswerRelevancyMetric;
import dev.agentworkshq.eval.core.metric.FaithfulnessMetric;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.CaseResult;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.MetricStatus;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class JudgeRunnerTest {

    private static EvalTarget target(Map<String, String> description, AtomicInteger calls) {
        return new EvalTarget() {
            @Override
            public String name() {
                return "fake-target";
            }

            @Override
            public TargetOutput run(EvalCase evalCase) {
                calls.incrementAndGet();
                return new TargetOutput("answer " + evalCase.id(), true,
                        List.of(new RetrievedChunk("k1", "d1", "d1.md", null, "chunk text", null)), null, Duration.ZERO, null);
            }

            @Override
            public Map<String, String> describe() {
                return description;
            }
        };
    }

    private static EvalTarget target(Map<String, String> description) {
        return target(description, new AtomicInteger());
    }

    private static List<ILoggingEvent> warningsDuring(Runnable action) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(EvalRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    @Test
    void snapshotHasJudgeTargetAndBudgetEntries() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-1", "{\"score\":4}");

        EvalRun run = runner().run(dataset("d", "a", "b", "c"), target(Map.of("model", "target-model", "temperature", "0.2")),
                List.of(new FaithfulnessMetric(judge), new AnswerRelevancyMetric(judge, 2, 3)), fast());

        assertThat(run.config()).containsEntry("runner.maxJudgeCalls", "500")
                .containsEntry("runner.judgeCallsUsed", String.valueOf(judge.calls()))
                .containsEntry("metric.faithfulness.judge", "judge-1").containsEntry("metric.faithfulness.repeats", "1")
                .containsEntry("metric.faithfulness.passScore", "4")
                .containsEntry("metric.faithfulness.promptVersion", "faithfulness-v1")
                .containsEntry("metric.answer_relevancy.repeats", "2").containsEntry("metric.answer_relevancy.passScore", "3")
                .containsEntry("metric.answer_relevancy.promptVersion", "answer-relevancy-v1")
                .containsEntry("target.model", "target-model").containsEntry("target.temperature", "0.2");
        assertThat(judge.calls()).isEqualTo(9);
        assertThat(run.config().get("runner.judgeCallsUsed")).isEqualTo("9");
        assertThat(run.config().keySet()).allMatch(k -> k.startsWith("runner.") || k.startsWith("metric.")
                || k.startsWith("target.") || k.startsWith("dataset.") || k.equals("metrics") || k.equals("library.version"));
    }

    @Test
    void customMaxJudgeCallsIsRecorded() {
        EvalRun run = runner().run(dataset("d", "a"), target(Map.of()), List.of(), fast().withMaxJudgeCalls(7));

        assertThat(run.config()).containsEntry("runner.maxJudgeCalls", "7").containsEntry("runner.judgeCallsUsed", "0");
    }

    @Test
    void smallBudgetGivesErrorsForLaterCasesAndEverythingElseCompletes() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-1", "{\"score\":5}");

        EvalRun run = runner().run(dataset("d", "a", "b", "c", "d"), target(Map.of()),
                List.of(new AnswerRelevancyMetric(judge), RunnerFixtures.passing("plain")), fast().withMaxJudgeCalls(2));

        assertThat(run.caseResults()).allMatch(r -> r.status() == CaseStatus.COMPLETED);
        assertThat(run.caseResults().stream().map(r -> r.metricResults().stream()
                .filter(m -> m.metricName().equals("answer_relevancy")).findFirst().orElseThrow().status()).toList())
                .containsExactly(MetricStatus.PASSED, MetricStatus.PASSED, MetricStatus.ERROR, MetricStatus.ERROR);
        assertThat(run.caseResults().get(3).metricResults()).extracting(MetricResult::reason)
                .contains("judge call budget exhausted");
        assertThat(run.caseResults()).allSatisfy(r -> assertThat(r.metricResults().stream()
                .filter(m -> m.metricName().equals("plain")).findFirst().orElseThrow().status()).isEqualTo(MetricStatus.PASSED));
        assertThat(judge.calls()).isEqualTo(2);
        assertThat(run.config()).containsEntry("runner.judgeCallsUsed", "2");
    }

    @Test
    void secondRunStartsWithAFreshBudget() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-1", "{\"score\":5}");
        EvalRunner runner = runner();
        List<Metric> metrics = List.of(new AnswerRelevancyMetric(judge));

        runner.run(dataset("d", "a", "b"), target(Map.of()), metrics, fast().withMaxJudgeCalls(2));
        EvalRun second = runner.run(dataset("d", "a", "b"), target(Map.of()), metrics, fast().withMaxJudgeCalls(2));

        assertThat(second.config()).containsEntry("runner.judgeCallsUsed", "2");
        assertThat(second.caseResults()).allSatisfy(r -> assertThat(r.metricResults().get(0).status()).isEqualTo(MetricStatus.PASSED));
    }

    @Test
    void warnsOnceWhenTheTargetModelIsTheJudge() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("same-model", "{\"score\":4}");

        List<ch.qos.logback.classic.spi.ILoggingEvent> warnings = warningsDuring(() -> runner().run(
                dataset("d", "a", "b"), target(Map.of("model", "same-model")),
                List.of(new FaithfulnessMetric(judge), new AnswerRelevancyMetric(judge)), fast().withConcurrency(2)));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage()).contains("same-model");
    }

    @Test
    void doesNotWarnWhenModelsDiffer() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-model", "{\"score\":4}");

        List<ILoggingEvent> warnings = warningsDuring(() -> runner().run(dataset("d", "a"),
                target(Map.of("model", "target-model")), List.of(new FaithfulnessMetric(judge)), fast()));
        List<ILoggingEvent> noModel = warningsDuring(() -> runner().run(dataset("d", "a"), target(Map.of()),
                List.of(new FaithfulnessMetric(judge)), fast()));

        assertThat(warnings).isEmpty();
        assertThat(noModel).isEmpty();
    }

    @Test
    void secretLookingMetricKeyIsRejectedBeforeAnythingRuns() {
        AtomicInteger targetCalls = new AtomicInteger();
        Metric leaky = new Metric() {
            @Override
            public String name() {
                return "leaky";
            }

            @Override
            public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
                return MetricResult.passed("leaky", 1.0, "x");
            }

            @Override
            public Map<String, String> runConfig() {
                return Map.of("api-key", MARKER);
            }
        };

        assertThatThrownBy(() -> runner().run(dataset("d", "a"), target(Map.of(), targetCalls), List.of(leaky), fast()))
                .isInstanceOf(BaseException.class).hasMessageContaining("metric.leaky.api-key").hasMessageNotContaining(MARKER)
                .extracting(e -> ((BaseException) e).getErrorCode()).isEqualTo("EVAL_E011");
        assertThat(targetCalls.get()).isZero();
    }

    @Test
    void secretLookingTargetKeyAndLongValuesAreRejectedWithTheKey() {
        Metric plain = RunnerFixtures.passing("plain");
        Metric longValue = new Metric() {
            @Override
            public String name() {
                return "wide";
            }

            @Override
            public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
                return MetricResult.passed("wide", 1.0, "x");
            }

            @Override
            public Map<String, String> runConfig() {
                return Map.of("note", "x".repeat(201));
            }
        };

        assertThatThrownBy(() -> runner().run(dataset("d", "a"), target(Map.of("password", "p")), List.of(plain), fast()))
                .hasMessageContaining("target.password");
        assertThatThrownBy(() -> runner().run(dataset("d", "a"), target(Map.of()), List.of(longValue), fast()))
                .hasMessageContaining("metric.wide.note").hasMessageNotContaining("xxxxxxxx");
        assertThatThrownBy(() -> runner().run(dataset("d", "a"), target(Map.of("model", "x".repeat(201))), List.of(plain), fast()))
                .hasMessageContaining("target.model");
    }

    @Test
    void judgeErrorsNeverLeakIntoCaseResults() {
        ScriptedJudgeModel judge = new ScriptedJudgeModel("judge-1", new IllegalStateException(MARKER));

        EvalRun run = runner().run(dataset("d", "a"), target(Map.of()), List.of(new FaithfulnessMetric(judge)), fast());

        CaseResult result = run.caseResults().get(0);
        assertThat(result.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(result.errorMessage()).isNull();
        assertThat(result.metricResults().get(0).status()).isEqualTo(MetricStatus.ERROR);
        assertThat(result.metricResults().get(0).reason()).isEqualTo("IllegalStateException").doesNotContain(MARKER);
    }

    @Test
    void metricsWithoutContextStillGetTheTwoArgumentCallThroughTheDefault() {
        Metric legacy = metric("legacy", (c, o) -> MetricResult.passed("legacy", 1.0, "ok"));

        EvalRun run = runner().run(dataset("d", "a"), target(Map.of()), List.of(legacy), fast());

        assertThat(run.caseResults().get(0).metricResults().get(0).status()).isEqualTo(MetricStatus.PASSED);
        assertThat(run.config()).doesNotContainKey("metric.legacy.judge");
    }
}
