package dev.agentworkshq.eval.core.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class ChatClientJudgeModelTest {

    private static final class FakeChatModel implements ChatModel {

        final List<Prompt> prompts = new ArrayList<>();
        final String reply;

        FakeChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    @Test
    void sendsOneSystemAndOneUserMessageWithoutTemplating() {
        FakeChatModel model = new FakeChatModel("{\"score\": 4}");
        ChatClientJudgeModel judge = new ChatClientJudgeModel(ChatClient.builder(model), "fake-judge");
        String system = "rules {braces} %s ${x} {{y}}";
        String user = "data {\"a\": 1} {question} <st>";

        String reply = judge.complete(system, user);

        assertThat(reply).isEqualTo("{\"score\": 4}");
        assertThat(judge.identifier()).isEqualTo("fake-judge");
        assertThat(model.prompts).hasSize(1);
        List<Message> messages = model.prompts.get(0).getInstructions();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo(system);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo(user);
        assertThat(model.prompts.get(0).getOptions().getTemperature()).isEqualTo(0.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", ""})
    void blankReplyThrows(String reply) {
        ChatClientJudgeModel judge = new ChatClientJudgeModel(ChatClient.builder(new FakeChatModel(reply)), "fake-judge");

        assertThatThrownBy(() -> judge.complete("s", "u")).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "api-key", "my_secret_model", "Bearer", "https://user:pw@host/model", "a\nb"})
    void rejectsUnsafeIdentifiers(String identifier) {
        ChatClient.Builder builder = ChatClient.builder(new FakeChatModel("x"));

        assertThatThrownBy(() -> new ChatClientJudgeModel(builder, identifier)).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsNullBuilderNullIdentifierAndLongIdentifier() {
        ChatClient.Builder builder = ChatClient.builder(new FakeChatModel("x"));

        assertThatThrownBy(() -> new ChatClientJudgeModel(null, "id")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new ChatClientJudgeModel(builder, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new ChatClientJudgeModel(builder, "x".repeat(101))).isInstanceOf(ValidationException.class);
        assertThat(new ChatClientJudgeModel(builder, "gpt-4o-mini").identifier()).isEqualTo("gpt-4o-mini");
    }
}
