package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.judge.RunContext;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.NameRules;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;

public final class SpringAiEvaluatorMetric implements Metric {

    private final String name;
    private final Evaluator evaluator;

    public SpringAiEvaluatorMetric(String name, Evaluator evaluator) {
        if (!NameRules.isValid(name)) {
            throw MetricArgs.bad("metric name is not valid");
        }
        if (evaluator == null) {
            throw MetricArgs.bad("evaluator must not be null");
        }
        this.name = name;
        this.evaluator = evaluator;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
        return evaluate(evalCase, output, RunContext.none());
    }

    @Override
    public MetricResult evaluate(EvalCase evalCase, TargetOutput output, RunContext context) {
        if (!output.answered()) {
            return MetricResult.skipped(name, "output was not answered");
        }
        if (!context.budget().tryAcquire(1)) {
            return MetricResult.error(name, "judge call budget exhausted");
        }
        List<Document> documents = new ArrayList<>();
        for (RetrievedChunk chunk : output.retrieved()) {
            if (!chunk.text().isBlank()) {
                documents.add(new Document(chunk.text()));
            }
        }
        try {
            EvaluationResponse response = evaluator.evaluate(
                    new EvaluationRequest(evalCase.question(), documents, output.answer()));
            return response.isPass()
                    ? MetricResult.passed(name, 1.0, "evaluator passed")
                    : MetricResult.failed(name, 0.0, "evaluator failed");
        } catch (RuntimeException e) {
            return MetricResult.error(name, JudgeMetric.simpleName(e));
        }
    }
}
