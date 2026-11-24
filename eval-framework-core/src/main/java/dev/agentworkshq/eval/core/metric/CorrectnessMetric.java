package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.judge.JudgeModel;
import dev.agentworkshq.eval.core.judge.JudgePromptBuilder.Section;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.List;

public final class CorrectnessMetric extends JudgeMetric {

    private static final String NAME = "correctness";
    private static final String RUBRIC = "Rate how well the ANSWER section agrees with the EXPECTED_ANSWER section "
            + "for the QUESTION section. 5 means the answer has the same meaning and no contradicting claims. "
            + "3 means it is partly correct or misses important points. 1 means it contradicts the expected answer "
            + "or is unrelated. Wording may differ, judge the meaning.";

    public CorrectnessMetric(JudgeModel judge) {
        this(judge, 1, 4, JudgeLimits.defaults());
    }

    public CorrectnessMetric(JudgeModel judge, int repeats, int passScore) {
        this(judge, repeats, passScore, JudgeLimits.defaults());
    }

    public CorrectnessMetric(JudgeModel judge, int repeats, int passScore, JudgeLimits limits) {
        super(NAME, "correctness-v1", RUBRIC, judge, repeats, passScore, limits);
    }

    @Override
    MetricResult precheck(EvalCase evalCase, TargetOutput output) {
        if (evalCase.expectNoAnswer()) {
            return MetricResult.skipped(NAME, "case expects no answer");
        }
        if (evalCase.expectedAnswer() == null || evalCase.expectedAnswer().isBlank()) {
            return MetricResult.skipped(NAME, "case has no expected answer");
        }
        return null;
    }

    @Override
    List<Section> sections(EvalCase evalCase, TargetOutput output, JudgeLimits limits) {
        return List.of(new Section("QUESTION", evalCase.question(), limits.question()),
                new Section("EXPECTED_ANSWER", evalCase.expectedAnswer(), limits.expectedAnswer()),
                new Section("ANSWER", output.answer(), limits.answer()));
    }
}
