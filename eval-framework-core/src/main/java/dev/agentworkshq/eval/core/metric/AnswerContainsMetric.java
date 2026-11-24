package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class AnswerContainsMetric implements Metric {

    private static final String NAME = "answer_contains";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
        List<String> phrases = evalCase.mustContain();
        if (phrases.isEmpty()) {
            return MetricResult.skipped(NAME, "case has no required phrases");
        }
        String answer = TextNormalizer.normalize(output.answer());
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < phrases.size(); i++) {
            if (!answer.contains(TextNormalizer.normalize(phrases.get(i)))) {
                missing.add(i + 1);
            }
        }
        int present = phrases.size() - missing.size();
        double score = (double) present / phrases.size();
        String reason = present + " of " + phrases.size() + " required phrases present";
        if (missing.isEmpty()) {
            return MetricResult.passed(NAME, score, reason);
        }
        String indexes = missing.stream().map(i -> "#" + i).collect(Collectors.joining(", "));
        return MetricResult.failed(NAME, score, reason + ", missing phrase" + (missing.size() > 1 ? "s " : " ") + indexes);
    }
}
