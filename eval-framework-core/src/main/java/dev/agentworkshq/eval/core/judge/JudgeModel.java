package dev.agentworkshq.eval.core.judge;

public interface JudgeModel {

    String identifier();

    String complete(String systemPrompt, String userPrompt);
}
