package dev.agentworkshq.eval.autoconfigure;

import dev.agentworkshq.eval.core.dataset.DatasetLoader;
import dev.agentworkshq.eval.core.judge.JudgeModel;
import dev.agentworkshq.eval.core.report.ReportWriter;
import dev.agentworkshq.eval.core.runner.EvalRunner;
import dev.agentworkshq.eval.core.store.FileRunStore;
import dev.agentworkshq.eval.core.store.RunStore;
import dev.agentworkshq.eval.core.store.StoreLimits;
import dev.agentworkshq.eval.core.suite.EvalSuite;
import java.nio.file.Path;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

@AutoConfiguration
@ConditionalOnProperty(prefix = "eval-framework", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(EvalFrameworkProperties.class)
public class EvalFrameworkAutoConfiguration {

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public DatasetLoader datasetLoader(EvalFrameworkProperties properties) {
        return new DatasetLoader(path(properties.dataset().root()), properties.dataset().limits());
    }

    @Bean
    @ConditionalOnMissingBean
    public EvalRunner evalRunner() {
        return new EvalRunner();
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "eval-framework.store", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RunStore runStore(EvalFrameworkProperties properties) {
        return new FileRunStore(path(properties.store().dir()), StoreLimits.defaults());
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "eval-framework.report", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ReportWriter reportWriter(EvalFrameworkProperties properties) {
        return new ReportWriter(path(properties.report().dir()));
    }

    @Bean
    @Lazy
    @ConditionalOnMissingBean
    public EvalSuite evalSuite(DatasetLoader datasetLoader, EvalRunner evalRunner, ObjectProvider<RunStore> runStore,
            ObjectProvider<ReportWriter> reportWriter, EvalFrameworkProperties properties) {
        return new EvalSuite(datasetLoader, evalRunner, runStore.getIfAvailable(), path(properties.baseline().dir()),
                StoreLimits.defaults(), reportWriter.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "eval-framework.judge", name = "identifier")
    public JudgeModel judgeModel(ObjectProvider<ChatClient.Builder> builders, EvalFrameworkProperties properties) {
        return new LazyJudgeModel(builders, properties.judge().identifier());
    }

    private static Path path(String value) {
        return Path.of(value).toAbsolutePath();
    }
}
