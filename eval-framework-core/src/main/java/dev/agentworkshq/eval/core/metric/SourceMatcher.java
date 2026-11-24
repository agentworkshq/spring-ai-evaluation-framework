package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public final class SourceMatcher {

    private SourceMatcher() {
    }

    public static String normalize(String source) {
        String value = source.trim().replace('\\', '/');
        return value.startsWith("./") ? value.substring(2) : value;
    }

    public static Set<String> normalizedSet(Collection<String> sources) {
        Set<String> result = new LinkedHashSet<>();
        for (String source : sources) {
            String value = normalize(source);
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    public static Set<String> expected(EvalCase evalCase) {
        return normalizedSet(evalCase.expectedSources());
    }

    public static boolean matches(String normalizedSource, RetrievedChunk chunk) {
        if (normalizedSource.isEmpty()) {
            return false;
        }
        return (chunk.source() != null && normalizedSource.equals(normalize(chunk.source())))
                || (chunk.documentId() != null && normalizedSource.equals(normalize(chunk.documentId())));
    }

    public static boolean isRelevant(Set<String> normalizedExpected, RetrievedChunk chunk) {
        for (String source : normalizedExpected) {
            if (matches(source, chunk)) {
                return true;
            }
        }
        return false;
    }
}
