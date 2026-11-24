package dev.agentworkshq.eval.core.judge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScriptedJudgeModel implements JudgeModel {

    private final String identifier;
    private final Object[] script;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> systemPrompts = Collections.synchronizedList(new ArrayList<>());
    private final List<String> userPrompts = Collections.synchronizedList(new ArrayList<>());

    public ScriptedJudgeModel(String identifier, Object... script) {
        this.identifier = identifier;
        this.script = script;
    }

    @Override
    public String identifier() {
        return identifier;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        systemPrompts.add(systemPrompt);
        userPrompts.add(userPrompt);
        int index = Math.min(calls.getAndIncrement(), script.length - 1);
        Object step = script[index];
        if (step instanceof RuntimeException error) {
            throw error;
        }
        return (String) step;
    }

    public int calls() {
        return calls.get();
    }

    public List<String> systemPrompts() {
        return List.copyOf(systemPrompts);
    }

    public List<String> userPrompts() {
        return List.copyOf(userPrompts);
    }
}
