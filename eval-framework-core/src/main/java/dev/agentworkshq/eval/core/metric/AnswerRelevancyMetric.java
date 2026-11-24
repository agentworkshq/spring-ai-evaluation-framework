package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.judge.JudgeModel;
import dev.agentworkshq.eval.core.judge.JudgePromptBuilder.Section;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.List;

public final class AnswerRelevancyMetric extends JudgeMetric {

    private static final String NAME = "answer_relevancy";
    private static final String RUBRIC = "Rate how well the ANSWER section addresses the QUESTION section. "
            + "5 means the answer directly and completely addresses the question. 3 means it addresses the question "
            + "only partly or with a lot of unrelated content. 1 means it does not address the question. "
            + "Do not judge whether the answer is factually correct.";

    public AnswerRelevancyMetric(JudgeModel judge) {
        this(judge, 1, 4, JudgeLimits.defaults());
    }

    public AnswerRelevancyMetric(JudgeModel judge, int repeats, int passScore) {
        this(judge, repeats, passScore, JudgeLimits.defaults());
    }

    public AnswerRelevancyMetric(JudgeModel judge, int repeats, int passScore, JudgeLimits limits) {
        super(NAME, "answer-relevancy-v1", RUBRIC, judge, repeats, passScore, limits);
    }

    @Override
    MetricResult precheck(EvalCase evalCase, TargetOutput output) {
        return skipUnlessAnswered(NAME, evalCase, output);
    }

    @Override
    List<Section> sections(EvalCase evalCase, TargetOutput output, JudgeLimits limits) {
        return List.of(new Section("QUESTION", evalCase.question(), limits.question()),
                new Section("ANSWER", output.answer(), limits.answer()));
    }
}
