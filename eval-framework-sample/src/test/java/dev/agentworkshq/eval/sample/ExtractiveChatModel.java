package dev.agentworkshq.eval.sample;

import dev.agentworkshq.rag.core.answer.RagPrompts;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class ExtractiveChatModel implements ChatModel {

    // an entry is "[n] filename", a line break, then the chunk text
    private static final Pattern FIRST_CHUNK =
            Pattern.compile("\\[1] [^\\n]*\\n(.*?)(?=\\n\\n\\[2] |\\z)", Pattern.DOTALL);
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s");

    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        String user = prompt.getUserMessage().getText();
        int start = user.indexOf(RagPrompts.CONTEXT_OPEN) + RagPrompts.CONTEXT_OPEN.length();
        int end = user.indexOf(RagPrompts.CONTEXT_CLOSE);
        Matcher chunk = FIRST_CHUNK.matcher(user.substring(start, end).strip());
        if (!chunk.find()) {
            throw new IllegalStateException("the prompt has no numbered context");
        }
        String sentence = SENTENCE_END.split(chunk.group(1).strip(), 2)[0].strip();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(sentence + " [1]"))));
    }

    int calls() {
        return calls.get();
    }
}
