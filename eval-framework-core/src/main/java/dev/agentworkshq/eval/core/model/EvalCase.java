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
        Map<String, String> attributes,
        List<String> mustContain,
        List<String> mustNotContain) {

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
        mustContain = phrases(mustContain, "mustContain");
        mustNotContain = phrases(mustNotContain, "mustNotContain");
        if (expectNoAnswer && (expectedAnswer != null || !expectedSources.isEmpty() || !mustContain.isEmpty())) {
            throw Checks.bad("field 'expectNoAnswer' cannot be combined with expectedAnswer, expectedSources or mustContain");
        }
    }

    private static List<String> phrases(List<String> values, String field) {
        List<String> list = Checks.list(values, field, 20);
        if (list.stream().anyMatch(String::isBlank)) {
            throw Checks.bad("field '" + field + "' must not contain blank entries");
        }
        if (list.stream().anyMatch(p -> p.length() > 500)) {
            throw Checks.bad("field '" + field + "' has an entry longer than 500 characters");
        }
        if (new HashSet<>(list).size() != list.size()) {
            throw Checks.bad("field '" + field + "' must not contain duplicates");
        }
        return List.copyOf(list.stream().sorted().toList());
    }
}
