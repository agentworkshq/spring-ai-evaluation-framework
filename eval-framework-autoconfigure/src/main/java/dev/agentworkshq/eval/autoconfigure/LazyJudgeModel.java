package dev.agentworkshq.eval.autoconfigure;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ServiceException;
import dev.agentworkshq.eval.core.judge.ChatClientJudgeModel;
import dev.agentworkshq.eval.core.judge.JudgeIdentifier;
import dev.agentworkshq.eval.core.judge.JudgeModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

final class LazyJudgeModel implements JudgeModel {

    private final ObjectProvider<ChatClient.Builder> builders;
    private final String identifier;
    private volatile JudgeModel delegate;

    LazyJudgeModel(ObjectProvider<ChatClient.Builder> builders, String identifier) {
        this.builders = builders;
        this.identifier = JudgeIdentifier.require(identifier);
    }

    @Override
    public String identifier() {
        return identifier;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return delegate().complete(systemPrompt, userPrompt);
    }

    private JudgeModel delegate() {
        JudgeModel current = delegate;
        if (current == null) {
            synchronized (this) {
                current = delegate;
                if (current == null) {
                    ChatClient.Builder builder = builders.getIfAvailable();
                    if (builder == null) {
                        throw new ServiceException(ErrorMessages.EVAL_E028,
                                "no ChatClient.Builder bean is available for the judge model, add a Spring AI chat model"
                                        + " starter or define your own JudgeModel bean");
                    }
                    current = new ChatClientJudgeModel(builder, identifier);
                    delegate = current;
                }
            }
        }
        return current;
    }
}
