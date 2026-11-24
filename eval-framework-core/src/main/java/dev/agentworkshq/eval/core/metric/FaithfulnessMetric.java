package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.judge.JudgeModel;
import dev.agentworkshq.eval.core.judge.JudgePromptBuilder.Section;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.List;

public final class FaithfulnessMetric extends JudgeMetric {

    private static final String NAME = "faithfulness";
    private static final String RUBRIC = "Rate how faithful the ANSWER section is to the CONTEXT section. "
            + "5 means every claim in the answer is supported by the context. 3 means about half of the claims are "
            + "supported. 1 means the answer is mostly unsupported or contradicts the context. "
            + "Do not reward claims that are true but not in the context.";

    public FaithfulnessMetric(JudgeModel judge) {
        this(judge, 1, 4, JudgeLimits.defaults());
    }

    public FaithfulnessMetric(JudgeModel judge, int repeats, int passScore) {
        this(judge, repeats, passScore, JudgeLimits.defaults());
    }

    public FaithfulnessMetric(JudgeModel judge, int repeats, int passScore, JudgeLimits limits) {
        super(NAME, "faithfulness-v1", RUBRIC, judge, repeats, passScore, limits);
    }

    @Override
    MetricResult precheck(EvalCase evalCase, TargetOutput output) {
        MetricResult skipped = skipUnlessAnswered(NAME, evalCase, output);
        if (skipped != null) {
            return skipped;
        }
        if (contextText(output.retrieved()).isEmpty()) {
            return MetricResult.failed(NAME, 0.0, "no retrieved context");
        }
        return null;
    }

    @Override
    List<Section> sections(EvalCase evalCase, TargetOutput output, JudgeLimits limits) {
        return List.of(new Section("CONTEXT", contextText(output.retrieved()), limits.context()),
                new Section("ANSWER", output.answer(), limits.answer()));
    }
}
