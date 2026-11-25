package dev.agentworkshq.eval.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.dataset.DatasetLoader;
import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.judge.JudgeModel;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.report.ReportWriter;
import dev.agentworkshq.eval.core.runner.EvalRunner;
import dev.agentworkshq.eval.core.runner.RunConfig;
import dev.agentworkshq.eval.core.store.RunStore;
import dev.agentworkshq.eval.core.suite.EvalOutcome;
import dev.agentworkshq.eval.core.suite.EvalRequest;
import dev.agentworkshq.eval.core.suite.EvalSuite;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EvalFrameworkAutoConfigurationTest {

    @TempDir
    Path dir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EvalFrameworkAutoConfiguration.class));

    private static Throwable rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Test
    void theBeansExistByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DatasetLoader.class);
            assertThat(context).hasSingleBean(EvalRunner.class);
            assertThat(context).hasSingleBean(ReportWriter.class);
            assertThat(context).hasSingleBean(RunStore.class);
            assertThat(context).hasSingleBean(EvalSuite.class);
            assertThat(context).hasSingleBean(EvalFrameworkProperties.class);
            assertThat(context).doesNotHaveBean(JudgeModel.class);
        });
    }

    @Test
    void theContextStartsWithoutCreatingDirectories() {
        runner.withPropertyValues("eval-framework.store.dir=" + dir.resolve("runs"),
                "eval-framework.report.dir=" + dir.resolve("reports")).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(dir.resolve("runs")).doesNotExist();
                    assertThat(dir.resolve("reports")).doesNotExist();
                });
    }

    @Test
    void disabledGivesNoBeans() {
        runner.withPropertyValues("eval-framework.enabled=false", "eval-framework.judge.identifier=judge-1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DatasetLoader.class);
            assertThat(context).doesNotHaveBean(EvalRunner.class);
            assertThat(context).doesNotHaveBean(ReportWriter.class);
            assertThat(context).doesNotHaveBean(RunStore.class);
            assertThat(context).doesNotHaveBean(EvalSuite.class);
            assertThat(context).doesNotHaveBean(JudgeModel.class);
            assertThat(context).doesNotHaveBean(EvalFrameworkProperties.class);
        });
    }

    @Test
    void aUserSuppliedBeanWins() {
        EvalRunner custom = new EvalRunner();
        JudgeModel customJudge = new JudgeModel() {
            @Override
            public String identifier() {
                return "mine";
            }

            @Override
            public String complete(String systemPrompt, String userPrompt) {
                return "{}";
            }
        };
        runner.withBean(EvalRunner.class, () -> custom).withBean(JudgeModel.class, () -> customJudge)
                .withPropertyValues("eval-framework.judge.identifier=judge-1").run(context -> {
                    assertThat(context).hasSingleBean(EvalRunner.class);
                    assertThat(context.getBean(EvalRunner.class)).isSameAs(custom);
                    assertThat(context).hasSingleBean(JudgeModel.class);
                    assertThat(context.getBean(JudgeModel.class)).isSameAs(customJudge);
                });
    }

    @Test
    void aUserSuppliedStoreAndWriterWin() {
        RunStore store = new RunStore() {
            @Override
            public void save(dev.agentworkshq.eval.core.store.StoredRun run) {
            }

            @Override
            public dev.agentworkshq.eval.core.store.StoredRun load(String runId) {
                return null;
            }

            @Override
            public List<dev.agentworkshq.eval.core.store.RunHeader> list() {
                return List.of();
            }

            @Override
            public java.util.Optional<dev.agentworkshq.eval.core.store.StoredRun> latest(String d, String t) {
                return java.util.Optional.empty();
            }
        };
        runner.withBean(RunStore.class, () -> store).run(context ->
                assertThat(context.getBean(RunStore.class)).isSameAs(store));
    }

    @Test
    void badPropertiesFailStartupWithAClearMessage() {
        List<Map.Entry<String, String>> bad = List.of(
                Map.entry("eval-framework.runner.concurrency=0", "concurrency"),
                Map.entry("eval-framework.runner.max-attempts=9", "maxAttempts"),
                Map.entry("eval-framework.runner.per-case-timeout=0s", "perCaseTimeout"),
                Map.entry("eval-framework.runner.max-judge-calls=-1", "maxJudgeCalls"),
                Map.entry("eval-framework.runner.max-cases=0", "maxCases"),
                Map.entry("eval-framework.dataset.root= ", "eval-framework.dataset.root"),
                Map.entry("eval-framework.dataset.max-cases=0", "max-cases"),
                Map.entry("eval-framework.baseline.dir= ", "eval-framework.baseline.dir"),
                Map.entry("eval-framework.store.dir= ", "eval-framework.store.dir"),
                Map.entry("eval-framework.report.dir= ", "eval-framework.report.dir"),
                Map.entry("eval-framework.judge.identifier=http://example.org/model", "judge identifier"),
                Map.entry("eval-framework.judge.identifier= ", "judge identifier"));
        for (Map.Entry<String, String> entry : bad) {
            runner.withPropertyValues(entry.getKey()).run(context -> {
                assertThat(context).as(entry.getKey()).hasFailed();
                assertThat(rootCause(context.getStartupFailure()).getMessage()).as(entry.getKey())
                        .contains(entry.getValue());
            });
        }
    }

    @Test
    void theStoreAndReportBeansFollowTheirFlags() {
        runner.withPropertyValues("eval-framework.store.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RunStore.class);
            assertThat(context).hasSingleBean(ReportWriter.class);
            assertThat(context).hasSingleBean(EvalSuite.class);
        });
        runner.withPropertyValues("eval-framework.report.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ReportWriter.class);
            assertThat(context).hasSingleBean(RunStore.class);
        });
        runner.withPropertyValues("eval-framework.store.enabled=false", "eval-framework.report.enabled=false",
                "eval-framework.dataset.root=" + dir).run(context -> {
                    assertThat(context.getBean(EvalSuite.class)).isNotNull();
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    void theJudgeBeanFollowsTheIdentifierAndTheContextStartsWithoutAChatClient() {
        runner.withPropertyValues("eval-framework.judge.identifier=judge-1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(JudgeModel.class);
            JudgeModel judge = context.getBean(JudgeModel.class);
            assertThat(judge.identifier()).isEqualTo("judge-1");

            assertThatThrownBy(() -> judge.complete("system", "user")).isInstanceOf(BaseException.class)
                    .hasMessageContaining("ChatClient.Builder")
                    .extracting(e -> ((BaseException) e).getErrorMessage()).isEqualTo(ErrorMessages.EVAL_E028);
            assertThatThrownBy(() -> judge.complete("system", "user")).hasMessageContaining("ChatClient.Builder");
        });
    }

    @Test
    void propertiesBindFromAMapAndBuildTheRunConfig() {
        runner.withPropertyValues(
                "eval-framework.dataset.root=data/sets",
                "eval-framework.dataset.max-file-bytes=1000",
                "eval-framework.dataset.max-cases=7",
                "eval-framework.baseline.dir=base",
                "eval-framework.store.dir=out/runs",
                "eval-framework.store.enabled=false",
                "eval-framework.report.dir=out/reports",
                "eval-framework.report.enabled=false",
                "eval-framework.runner.concurrency=4",
                "eval-framework.runner.per-case-timeout=5s",
                "eval-framework.runner.per-metric-timeout=2m",
                "eval-framework.runner.total-timeout=10m",
                "eval-framework.runner.max-attempts=3",
                "eval-framework.runner.max-cases=50",
                "eval-framework.runner.max-judge-calls=20",
                "eval-framework.judge.identifier=judge-x").run(context -> {
                    EvalFrameworkProperties p = context.getBean(EvalFrameworkProperties.class);

                    assertThat(p.dataset().root()).isEqualTo("data/sets");
                    assertThat(p.dataset().limits().maxFileBytes()).isEqualTo(1000);
                    assertThat(p.dataset().limits().maxCases()).isEqualTo(7);
                    assertThat(p.baseline().dir()).isEqualTo("base");
                    assertThat(p.store().dir()).isEqualTo("out/runs");
                    assertThat(p.store().enabled()).isFalse();
                    assertThat(p.report().dir()).isEqualTo("out/reports");
                    assertThat(p.report().enabled()).isFalse();
                    assertThat(p.judge().identifier()).isEqualTo("judge-x");
                    assertThat(p.runner().toRunConfig()).isEqualTo(RunConfig.defaults().withConcurrency(4)
                            .withPerCaseTimeout(Duration.ofSeconds(5)).withPerMetricTimeout(Duration.ofMinutes(2))
                            .withTotalTimeout(Duration.ofMinutes(10)).withMaxAttempts(3).withMaxCases(50)
                            .withMaxJudgeCalls(20));
                });
    }

    @Test
    void theDefaultsMatchTheDocumentedValues() {
        runner.run(context -> {
            EvalFrameworkProperties p = context.getBean(EvalFrameworkProperties.class);

            assertThat(p.enabled()).isTrue();
            assertThat(p.dataset().root()).isEqualTo("src/test/resources/eval-datasets");
            assertThat(p.dataset().maxFileBytes()).isEqualTo(5 * 1024 * 1024);
            assertThat(p.dataset().maxCases()).isEqualTo(1000);
            assertThat(p.baseline().dir()).isEqualTo("src/test/resources/eval-baselines");
            assertThat(p.store().dir()).isEqualTo("target/eval-runs");
            assertThat(p.store().enabled()).isTrue();
            assertThat(p.report().dir()).isEqualTo("target/eval-reports");
            assertThat(p.report().enabled()).isTrue();
            assertThat(p.judge().identifier()).isNull();
            assertThat(p.runner().toRunConfig()).isEqualTo(RunConfig.defaults());
        });
    }

    @Test
    void theAutoConfiguredSuiteRunsEndToEnd() throws IOException {
        Path datasets = Files.createDirectory(dir.resolve("datasets"));
        Files.writeString(datasets.resolve("sample.yaml"), """
                name: sample
                cases:
                  - id: a
                    question: Question a
                """);
        runner.withPropertyValues(
                "eval-framework.dataset.root=" + datasets,
                "eval-framework.baseline.dir=" + dir.resolve("baselines"),
                "eval-framework.store.dir=" + dir.resolve("runs"),
                "eval-framework.report.dir=" + dir.resolve("reports")).run(context -> {
                    EvalTarget target = new EvalTarget() {
                        @Override
                        public String name() {
                            return "fake";
                        }

                        @Override
                        public TargetOutput run(EvalCase evalCase) {
                            return new TargetOutput("answer", true, null, null, Duration.ZERO, null);
                        }
                    };
                    Metric metric = new Metric() {
                        @Override
                        public String name() {
                            return "m";
                        }

                        @Override
                        public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
                            return MetricResult.passed("m", 1.0, "ok");
                        }
                    };

                    EvalOutcome outcome = context.getBean(EvalSuite.class).evaluate("sample.yaml", target, List.of(metric),
                            EvalRequest.defaults().withRunConfig(context.getBean(EvalFrameworkProperties.class)
                                    .runner().toRunConfig().withRunId("auto-1")));

                    assertThat(outcome.passesGate()).isTrue();
                    assertThat(dir.resolve("runs").resolve("run-auto-1.json")).exists();
                    assertThat(dir.resolve("reports").resolve("eval-report-auto-1.md")).exists();
                    assertThat(dir.resolve("baselines")).doesNotExist();
                });
    }
}
