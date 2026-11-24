package dev.agentworkshq.eval.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "eval-framework")
public record EvalFrameworkProperties(@DefaultValue("true") boolean enabled) {
}
