package dev.agentworkshq.eval.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.dataset.DatasetFormat;
import dev.agentworkshq.eval.core.dataset.DatasetLimits;
import dev.agentworkshq.eval.core.dataset.DatasetLoader;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EvalCaseTest {

    private static EvalCase simple() {
        return new EvalCase("a", "q", null, null, false, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "A1", "0", "case-1", "case_1", "v1.2", "a.b-c_d"})
    void nameRuleAccepts(String name) {
        assertThat(NameRules.isValid(name)).isTrue();
        assertThat(NameRules.isValid("x".repeat(100))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "..", "-x", ".hidden", "_x", "a b", "a\nb", "a/b", "a\\b", "a:b", "éa"})
    void nameRuleRejects(String name) {
        assertThat(NameRules.isValid(name)).isFalse();
    }

    @Test
    void nameRuleRejectsNullAndTooLong() {
        assertThat(NameRules.isValid(null)).isFalse();
        assertThat(NameRules.isValid("x".repeat(101))).isFalse();
        assertThatThrownBy(() -> NameRules.require("x".repeat(101), "id")).isInstanceOf(ValidationException.class);
    }

    @Test
    void validCaseKeepsAllFields() {
        EvalCase c = new EvalCase("id-1", "Question?", "Answer", List.of("a.pdf"), false, List.of("t"), Map.of("k", "v"));

        assertThat(c.id()).isEqualTo("id-1");
        assertThat(c.question()).isEqualTo("Question?");
        assertThat(c.expectedAnswer()).isEqualTo("Answer");
        assertThat(c.expectedSources()).containsExactly("a.pdf");
        assertThat(c.tags()).containsExactly("t");
        assertThat(c.attributes()).containsEntry("k", "v");
    }

    @Test
    void rejectsInvalidId() {
        assertThatThrownBy(() -> new EvalCase("bad id", "q", null, null, false, null, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalCase(null, "q", null, null, false, null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsBadQuestion() {
        assertThatThrownBy(() -> new EvalCase("a", " ", null, null, false, null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalCase("a", null, null, null, false, null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalCase("a", "q".repeat(4001), null, null, false, null, null))
                .isInstanceOf(ValidationException.class);
        assertThat(new EvalCase("a", "q".repeat(4000), null, null, false, null, null).question()).hasSize(4000);
    }

    @Test
    void rejectsLongExpectedAnswer() {
        assertThatThrownBy(() -> new EvalCase("a", "q", "x".repeat(8001), null, false, null, null))
                .isInstanceOf(ValidationException.class);
        assertThat(new EvalCase("a", "q", "x".repeat(8000), null, false, null, null)).isNotNull();
    }

    @Test
    void rejectsBadSources() {
        List<String> blank = new ArrayList<>(List.of("a"));
        blank.add(" ");
        assertThatThrownBy(() -> new EvalCase("a", "q", null, blank, false, null, null)).isInstanceOf(ValidationException.class);
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new EvalCase("a", "q", null, withNull, false, null, null)).isInstanceOf(ValidationException.class);
        List<String> many = IntStream.range(0, 51).mapToObj(i -> "s" + i).toList();
        assertThatThrownBy(() -> new EvalCase("a", "q", null, many, false, null, null)).isInstanceOf(ValidationException.class);
        assertThat(new EvalCase("a", "q", null, many.subList(0, 50), false, null, null).expectedSources()).hasSize(50);
    }

    @Test
    void rejectsBadTags() {
        assertThatThrownBy(() -> new EvalCase("a", "q", null, null, false, List.of("t", "t"), null))
                .isInstanceOf(ValidationException.class);
        List<String> many = IntStream.range(0, 21).mapToObj(i -> "t" + i).toList();
        assertThatThrownBy(() -> new EvalCase("a", "q", null, null, false, many, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalCase("a", "q", null, null, false, List.of("x".repeat(51)), null))
                .isInstanceOf(ValidationException.class);
        assertThat(new EvalCase("a", "q", null, null, false, List.of("x".repeat(50)), null).tags()).hasSize(1);
    }

    @Test
    void storesTagsAndAttributesSorted() {
        Map<String, String> unsorted = new HashMap<>();
        unsorted.put("z", "1");
        unsorted.put("m", "2");
        unsorted.put("a", "3");

        EvalCase c = new EvalCase("a", "q", null, null, false, List.of("c", "a", "b"), unsorted);

        assertThat(c.tags()).containsExactly("a", "b", "c");
        assertThat(c.attributes().keySet()).containsExactly("a", "m", "z");
    }

    @Test
    void rejectsTooManyAttributes() {
        Map<String, String> many = new HashMap<>();
        IntStream.range(0, 21).forEach(i -> many.put("k" + i, "v"));
        assertThatThrownBy(() -> new EvalCase("a", "q", null, null, false, null, many)).isInstanceOf(ValidationException.class);
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("k", null);
        assertThatThrownBy(() -> new EvalCase("a", "q", null, null, false, null, nullValue))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsNoAnswerWithAnswerOrSources() {
        assertThatThrownBy(() -> new EvalCase("a", "q", "x", null, true, null, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalCase("a", "q", null, List.of("s"), true, null, null))
                .isInstanceOf(ValidationException.class);
        assertThat(new EvalCase("a", "q", null, null, true, null, null).expectNoAnswer()).isTrue();
    }

    @Test
    void collectionsAreUnmodifiableAndCopied() {
        List<String> sources = new ArrayList<>(List.of("s"));
        List<String> tags = new ArrayList<>(List.of("t"));
        Map<String, String> attributes = new HashMap<>(Map.of("k", "v"));

        EvalCase c = new EvalCase("a", "q", null, sources, false, tags, attributes);
        sources.add("other");
        tags.add("other");
        attributes.put("other", "x");

        assertThat(c.expectedSources()).containsExactly("s");
        assertThat(c.tags()).containsExactly("t");
        assertThat(c.attributes()).hasSize(1);
        assertThatThrownBy(() -> c.expectedSources().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.tags().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> c.attributes().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullCollectionsBecomeEmpty() {
        EvalCase c = simple();

        assertThat(c.expectedSources()).isEmpty();
        assertThat(c.tags()).isEmpty();
        assertThat(c.attributes()).isEmpty();
    }

    @Test
    void invalidIdIsNotEchoedInMessages() {
        String bad = "SECRET ID/with space";

        assertThatThrownBy(() -> new EvalCase(bad, "q", null, null, false, null, null))
                .hasMessageNotContaining("SECRET");

        DatasetLoader loader = new DatasetLoader(Path.of("."), DatasetLimits.defaults());
        assertThatThrownBy(() -> loader.parse("d", DatasetFormat.JSONL, "{\"id\": \"" + bad + "\", \"question\": \"q\"}\n"))
                .isInstanceOf(ValidationException.class)
                .hasMessageNotContaining("SECRET");
    }

    @Test
    void duplicateIdMessageUsesOnlyValidatedId(@TempDir Path dir) {
        DatasetLoader loader = new DatasetLoader(dir, DatasetLimits.defaults());
        String jsonl = "{\"id\": \"dup\", \"question\": \"q\"}\n{\"id\": \"dup\", \"question\": \"q\"}\n";

        assertThatThrownBy(() -> loader.parse("d", DatasetFormat.JSONL, jsonl)).hasMessageContaining("'dup'");
    }
}
