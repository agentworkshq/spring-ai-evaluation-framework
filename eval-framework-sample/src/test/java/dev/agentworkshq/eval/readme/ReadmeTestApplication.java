package dev.agentworkshq.eval.readme;

import dev.agentworkshq.eval.autoconfigure.EvalFrameworkAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;

// outside the sample package so that the sample application never scans it, and without the rag-engine beans
@SpringBootConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration(EvalFrameworkAutoConfiguration.class)
class ReadmeTestApplication {
}
