package dev.agentworkshq.eval.core.judge;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;

public final class ChatClientJudgeModel implements JudgeModel {

    private final ChatClient client;
    private final String identifier;

    public ChatClientJudgeModel(ChatClient.Builder builder, String identifier) {
        if (builder == null) {
            throw new ValidationException(ErrorMessages.EVAL_E010, "chat client builder must not be null");
        }
        this.identifier = JudgeIdentifier.require(identifier);
        this.client = builder.clone().build();
    }

    @Override
    public String identifier() {
        return identifier;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        String reply = client.prompt()
                .messages(new SystemMessage(systemPrompt), new UserMessage(userPrompt))
                .options(ChatOptions.builder().temperature(0.0))
                .call()
                .content();
        if (reply == null || reply.isBlank()) {
            throw new IllegalStateException("judge model returned an empty reply");
        }
        return reply;
    }
}
