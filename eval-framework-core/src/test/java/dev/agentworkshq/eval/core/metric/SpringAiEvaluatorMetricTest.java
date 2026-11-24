package dev.agentworkshq.eval.core.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.judge.RunContext;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.MetricStatus;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;

class SpringAiEvaluatorMetricTest {

    private static final EvalCase CASE = new EvalCase("c1", "the question", null, null, false, null, null, null, null);

    private static TargetOutput output(boolean answered) {
        List<RetrievedChunk> chunks = List.of(new RetrievedChunk("k1", "d1", "d1.md", null, "first chunk", null),
                new RetrievedChunk("k2", "d2", "d2.md", null, "  ", null), new RetrievedChunk("k3", "d3", "d3.md", null, "third chunk", null));
        return new TargetOutput("the answer", answered, chunks, null, Duration.ZERO, null);
    }

    @Test
    void passGivesOneAndBuildsTheRequestFromQuestionChunksAndAnswer() {
        AtomicReference<EvaluationRequest> seen = new AtomicReference<>();
        RunContext context = RunContext.of(5);
        SpringAiEvaluatorMetric metric = new SpringAiEvaluatorMetric("spring_ai_relevancy", request -> {
            seen.set(request);
            return new EvaluationResponse(true, "feedback", Map.of());
        });

        MetricResult result = metric.evaluate(CASE, output(true), context);

        assertThat(result.status()).isEqualTo(MetricStatus.PASSED);
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(seen.get().getUserText()).isEqualTo("the question");
        assertThat(seen.get().getResponseContent()).isEqualTo("the answer");
        assertThat(seen.get().getDataList()).extracting(Document::getText).containsExactly("first chunk", "third chunk");
        assertThat(context.budget().used()).isEqualTo(1);
        assertThat(metric.name()).isEqualTo("spring_ai_relevancy");
    }

    @Test
    void failGivesZero() {
        SpringAiEvaluatorMetric metric = new SpringAiEvaluatorMetric("m", request -> new EvaluationResponse(false, "no", Map.of()));

        MetricResult result = metric.evaluate(CASE, output(true), RunContext.of(1));

        assertThat(result.status()).isEqualTo(MetricStatus.FAILED);
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void skipsWhenNotAnsweredWithoutSpendingBudget() {
        AtomicInteger calls = new AtomicInteger();
        RunContext context = RunContext.of(5);
        SpringAiEvaluatorMetric metric = new SpringAiEvaluatorMetric("m", request -> {
            calls.incrementAndGet();
            return new EvaluationResponse(true, "", Map.of());
        });

        MetricResult result = metric.evaluate(CASE, output(false), context);

        assertThat(result.status()).isEqualTo(MetricStatus.SKIPPED);
        assertThat(calls.get()).isZero();
        assertThat(context.budget().used()).isZero();
    }

    @Test
    void exceptionBecomesErrorWithOnlyTheClassName() {
        SpringAiEvaluatorMetric metric = new SpringAiEvaluatorMetric("m", request -> {
            throw new IllegalArgumentException("detail with the answer");
        });

        MetricResult result = metric.evaluate(CASE, output(true), RunContext.of(1));

        assertThat(result.status()).isEqualTo(MetricStatus.ERROR);
        assertThat(result.reason()).isEqualTo("IllegalArgumentException");
    }

    @Test
    void exhaustedBudgetGivesErrorWithoutCallingTheEvaluator() {
        AtomicInteger calls = new AtomicInteger();
        SpringAiEvaluatorMetric metric = new SpringAiEvaluatorMetric("m", request -> {
            calls.incrementAndGet();
            return new EvaluationResponse(true, "", Map.of());
        });

        MetricResult none = metric.evaluate(CASE, output(true), RunContext.none());
        MetricResult twoArguments = metric.evaluate(CASE, output(true));

        assertThat(none.status()).isEqualTo(MetricStatus.ERROR);
        assertThat(none.reason()).isEqualTo("judge call budget exhausted");
        assertThat(twoArguments).isEqualTo(none);
        assertThat(calls.get()).isZero();
    }

    @Test
    void validatesNameAndEvaluator() {
        assertThatThrownBy(() -> new SpringAiEvaluatorMetric("bad name", request -> null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new SpringAiEvaluatorMetric(null, request -> null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new SpringAiEvaluatorMetric("m", null)).isInstanceOf(ValidationException.class);
    }
}
