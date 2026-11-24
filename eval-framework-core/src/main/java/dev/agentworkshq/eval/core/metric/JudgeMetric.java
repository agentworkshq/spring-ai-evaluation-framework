package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.judge.JudgeIdentifier;
import dev.agentworkshq.eval.core.judge.JudgeModel;
import dev.agentworkshq.eval.core.judge.JudgePromptBuilder;
import dev.agentworkshq.eval.core.judge.JudgeReplyParser;
import dev.agentworkshq.eval.core.judge.RunContext;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Collectors;

public abstract class JudgeMetric implements Metric {

    private final String name;
    private final String promptVersion;
    private final String systemPrompt;
    private final JudgeModel judge;
    private final String identifier;
    private final int repeats;
    private final int passScore;
    private final JudgeLimits limits;
    private final JudgePromptBuilder promptBuilder = new JudgePromptBuilder();

    JudgeMetric(String name, String promptVersion, String rubric, JudgeModel judge, int repeats, int passScore,
            JudgeLimits limits) {
        if (judge == null || limits == null) {
            throw MetricArgs.bad("judge model and limits must not be null");
        }
        if (repeats < 1 || repeats > 5) {
            throw MetricArgs.bad("repeats must be between 1 and 5");
        }
        if (passScore < 1 || passScore > 5) {
            throw MetricArgs.bad("passScore must be between 1 and 5");
        }
        this.name = name;
        this.promptVersion = promptVersion;
        this.systemPrompt = JudgePromptBuilder.systemPrompt(rubric);
        this.judge = judge;
        this.identifier = JudgeIdentifier.require(judge.identifier());
        this.repeats = repeats;
        this.passScore = passScore;
        this.limits = limits;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final Map<String, String> runConfig() {
        return Map.of("judge", identifier, "repeats", String.valueOf(repeats), "passScore", String.valueOf(passScore),
                "promptVersion", promptVersion);
    }

    @Override
    public final MetricResult evaluate(EvalCase evalCase, TargetOutput output) {
        return evaluate(evalCase, output, RunContext.none());
    }

    @Override
    public final MetricResult evaluate(EvalCase evalCase, TargetOutput output, RunContext context) {
        MetricResult early = precheck(evalCase, output);
        if (early != null) {
            return early;
        }
        if (!context.budget().tryAcquire(repeats)) {
            return MetricResult.error(name, "judge call budget exhausted");
        }
        JudgePromptBuilder.Prompt prompt = promptBuilder.build(sections(evalCase, output, limits));
        List<Integer> scores = new ArrayList<>();
        for (int i = 0; i < repeats; i++) {
            if (Thread.currentThread().isInterrupted()) {
                return MetricResult.error(name, "interrupted");
            }
            String reply;
            try {
                reply = judge.complete(systemPrompt, prompt.userPrompt());
            } catch (RuntimeException e) {
                return MetricResult.error(name, simpleName(e));
            }
            OptionalInt score = JudgeReplyParser.parseScore(reply);
            if (score.isEmpty()) {
                return MetricResult.error(name, "invalid judge reply");
            }
            scores.add(score.getAsInt());
        }
        double mean = scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        double score = (mean - 1) / 4;
        String reason = "judge scores " + scores.stream().map(String::valueOf).collect(Collectors.joining(","))
                + String.format(Locale.ROOT, " mean %.2f pass score %d", mean, passScore)
                + (prompt.truncated() ? ", input truncated" : "");
        return MetricArgs.reaches(mean, passScore)
                ? MetricResult.passed(name, score, reason)
                : MetricResult.failed(name, score, reason);
    }

    abstract MetricResult precheck(EvalCase evalCase, TargetOutput output);

    abstract List<JudgePromptBuilder.Section> sections(EvalCase evalCase, TargetOutput output, JudgeLimits limits);

    static MetricResult skipUnlessAnswered(String name, EvalCase evalCase, TargetOutput output) {
        if (evalCase.expectNoAnswer()) {
            return MetricResult.skipped(name, "case expects no answer");
        }
        if (!output.answered()) {
            return MetricResult.skipped(name, "output was not answered");
        }
        if (output.answer().isBlank()) {
            return MetricResult.skipped(name, "answer is blank");
        }
        return null;
    }

    static String contextText(List<RetrievedChunk> chunks) {
        StringBuilder text = new StringBuilder();
        int index = 0;
        for (RetrievedChunk chunk : chunks) {
            if (!chunk.text().isBlank()) {
                text.append(text.isEmpty() ? "" : "\n\n").append("[").append(++index).append("] ").append(chunk.text());
            }
        }
        return text.toString();
    }

    static String simpleName(Throwable error) {
        String simple = error.getClass().getSimpleName();
        return simple.isEmpty() ? error.getClass().getName() : simple;
    }
}
