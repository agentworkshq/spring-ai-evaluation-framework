package dev.agentworkshq.eval.readme;

import dev.agentworkshq.eval.autoconfigure.EvalFrameworkProperties;
import dev.agentworkshq.eval.core.metric.AnswerContainsMetric;
import dev.agentworkshq.eval.core.metric.LatencyMetric;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.suite.EvalOutcome;
import dev.agentworkshq.eval.core.suite.EvalRequest;
import dev.agentworkshq.eval.core.suite.EvalSuite;
import dev.agentworkshq.eval.core.suite.Thresholds;
import dev.agentworkshq.eval.core.target.EvalTarget;
import dev.agentworkshq.eval.junit.EvalAssertions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// README-BEGIN quickstart
@SpringBootTest
class SupportBotEvalTest {

    @Autowired
    EvalSuite suite;

    @Autowired
    EvalFrameworkProperties properties;

    @Test
    void supportBotMeetsTheGate() {
        EvalTarget target = new EvalTarget() {
            public String name() {
                return "support-bot";
            }

            public TargetOutput run(EvalCase evalCase) {
                return new TargetOutput("Reset it under Settings.", true, List.of(), List.of(), Duration.ZERO, Map.of());
            }
        };
        EvalRequest request = EvalRequest.defaults()
                .withRunConfig(properties.runner().toRunConfig())
                .withThresholds(Thresholds.defaults().withMinPassRate("answer_contains", 1.0));

        EvalOutcome outcome = suite.evaluate("support.yaml", target,
                List.of(new AnswerContainsMetric(), new LatencyMetric(Duration.ofSeconds(2))), request);

        EvalAssertions.assertGate(outcome);
    }
}
// README-END quickstart
