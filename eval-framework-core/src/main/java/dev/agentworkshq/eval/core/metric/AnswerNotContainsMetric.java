package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class AnswerNotContainsMetric implements Metric {

    private static final String NAME = "answer_not_contains";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
        List<String> phrases = evalCase.mustNotContain();
        if (phrases.isEmpty()) {
            return MetricResult.skipped(NAME, "case has no forbidden phrases");
        }
        String answer = TextNormalizer.normalize(output.answer());
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < phrases.size(); i++) {
            if (answer.contains(TextNormalizer.normalize(phrases.get(i)))) {
                found.add(i + 1);
            }
        }
        int absent = phrases.size() - found.size();
        double score = (double) absent / phrases.size();
        String reason = absent + " of " + phrases.size() + " forbidden phrases absent";
        if (found.isEmpty()) {
            return MetricResult.passed(NAME, score, reason);
        }
        String indexes = found.stream().map(i -> "#" + i).collect(Collectors.joining(", "));
        return MetricResult.failed(NAME, score, reason + ", found phrase" + (found.size() > 1 ? "s " : " ") + indexes);
    }
}
