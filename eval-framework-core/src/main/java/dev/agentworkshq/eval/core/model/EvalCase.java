package dev.agentworkshq.eval.core.model;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

public record EvalCase(
        String id,
        String question,
        String expectedAnswer,
        List<String> expectedSources,
        boolean expectNoAnswer,
        List<String> tags,
        Map<String, String> attributes) {

    public EvalCase {
        NameRules.require(id, "id");
        Checks.maxLength(Checks.required(question, "question"), "question", 4000);
        Checks.maxLength(expectedAnswer, "expectedAnswer", 8000);
        expectedSources = Checks.list(expectedSources, "expectedSources", 50);
        if (expectedSources.stream().anyMatch(String::isBlank)) {
            throw Checks.bad("field 'expectedSources' must not contain blank entries");
        }
        tags = Checks.list(tags, "tags", 20);
        if (new HashSet<>(tags).size() != tags.size()) {
            throw Checks.bad("field 'tags' must not contain duplicates");
        }
        if (tags.stream().anyMatch(t -> t.length() > 50)) {
            throw Checks.bad("field 'tags' has an entry longer than 50 characters");
        }
        tags = List.copyOf(tags.stream().sorted().toList());
        attributes = Checks.sortedMap(attributes, "attributes", 20);
        if (expectNoAnswer && (expectedAnswer != null || !expectedSources.isEmpty())) {
            throw Checks.bad("field 'expectNoAnswer' cannot be combined with expectedAnswer or expectedSources");
        }
    }
}
