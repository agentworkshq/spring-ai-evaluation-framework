package dev.agentworkshq.eval.readme;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentworkshq.eval.core.judge.ChatClientJudgeModel;
import dev.agentworkshq.eval.core.judge.JudgeModel;
import dev.agentworkshq.eval.core.metric.AnswerRelevancyMetric;
import dev.agentworkshq.eval.core.metric.FaithfulnessMetric;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.metric.SpringAiEvaluatorMetric;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.EvalDataset;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.MetricStatus;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.runner.EvalRunner;
import dev.agentworkshq.eval.core.runner.RunConfig;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class ReadmeJudgeTest {

    @Test
    void theJudgeExampleScoresEveryMetric() {
        ChatClient.Builder chatClientBuilder = ChatClient.builder(new FakeJudge());
        EvalDataset dataset = new EvalDataset("support", List.of(
                new EvalCase("reset", "How do I reset it?", null, null, false, null, null, null, null)));
        EvalTarget target = new EvalTarget() {
            @Override
            public String name() {
                return "support-bot";
            }

            @Override
            public TargetOutput run(EvalCase evalCase) {
                RetrievedChunk chunk = new RetrievedChunk("c1", "d1", "reset.txt", null, "Reset it under Settings.", null);
                return new TargetOutput("Reset it under Settings.", true, List.of(chunk), List.of(), Duration.ZERO,
                        Map.of());
            }
        };

        // README-BEGIN judge
        JudgeModel judge = new ChatClientJudgeModel(chatClientBuilder, "gpt-4o-mini");
        List<Metric> metrics = List.of(
                new FaithfulnessMetric(judge, 3, 4),
                new AnswerRelevancyMetric(judge),
                new SpringAiEvaluatorMetric("spring_ai_relevancy", new RelevancyEvaluator(chatClientBuilder)));
        EvalRun run = new EvalRunner().run(dataset, target, metrics,
                RunConfig.defaults().withMaxJudgeCalls(200).withPerMetricTimeout(Duration.ofSeconds(120)));
        // README-END judge

        assertThat(run.caseResults().get(0).metricResults())
                .hasSize(3)
                .extracting(MetricResult::status)
                .containsOnly(MetricStatus.PASSED);
    }

    // the judge metrics send a system message and expect JSON, the Spring AI evaluator expects yes or no
    private static final class FakeJudge implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean strict = prompt.getInstructions().stream().anyMatch(SystemMessage.class::isInstance);
            String reply = strict ? "{\"score\": 5}" : "YES";
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }
}
