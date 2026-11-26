package dev.agentworkshq.eval.core.runner;

import static dev.agentworkshq.eval.core.runner.RunnerFixtures.dataset;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.fast;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.output;
import static dev.agentworkshq.eval.core.runner.RunnerFixtures.runner;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class EvalRunnerLogTest {

    @Test
    void theModelWarningCleansTheValue() {
        assertThat(warning("bad\nmodel\u001b[31m")).contains("'bad model [31m'").doesNotContain("\n", "\u001b");
    }

    @Test
    void theModelWarningCutsALongValue() {
        assertThat(warning("m".repeat(200))).contains("m".repeat(61) + "...").doesNotContain("m".repeat(62));
    }

    private static String warning(String model) {
        EvalTarget target = new EvalTarget() {
            @Override
            public String name() {
                return "t";
            }

            @Override
            public TargetOutput run(EvalCase evalCase) {
                return output("x");
            }

            @Override
            public Map<String, String> describe() {
                return Map.of("model", model);
            }
        };
        Metric judged = new Metric() {
            @Override
            public String name() {
                return "m";
            }

            @Override
            public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
                return MetricResult.passed("m", 1.0, "ok");
            }

            @Override
            public Map<String, String> runConfig() {
                return Map.of("judge", model);
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(EvalRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            runner().run(dataset("d", "a"), target, List.of(judged), fast());
        } finally {
            logger.detachAppender(appender);
        }
        List<String> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings).hasSize(1);
        return warnings.getFirst();
    }
}
