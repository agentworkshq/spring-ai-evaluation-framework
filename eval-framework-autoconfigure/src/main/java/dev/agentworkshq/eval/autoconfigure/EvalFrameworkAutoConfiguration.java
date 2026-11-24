package dev.agentworkshq.eval.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@EnableConfigurationProperties(EvalFrameworkProperties.class)
public class EvalFrameworkAutoConfiguration {
}
