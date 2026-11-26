package dev.agentworkshq.eval.sample;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class OfflineModelsConfiguration {

    @Bean
    EmbeddingModel embeddingModel() {
        return new HashingEmbeddingModel();
    }

    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    ExtractiveChatModel chatModel() {
        return new ExtractiveChatModel();
    }

    @Bean
    ChatClient.Builder chatClientBuilder(ExtractiveChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
