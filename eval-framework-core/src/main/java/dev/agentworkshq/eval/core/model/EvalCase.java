package dev.agentworkshq.eval.core.model;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record EvalCase(
        String id,
        String question,
        String expectedAnswer,
        List<String> expectedSources,
        boolean expectNoAnswer,
        List<String> tags,
        Map<String, String> attributes) {

    public EvalCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        expectedSources = expectedSources == null ? List.of() : List.copyOf(expectedSources);
        tags = tags == null ? List.of() : List.copyOf(tags);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
