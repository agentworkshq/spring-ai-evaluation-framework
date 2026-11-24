package dev.agentworkshq.eval.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class EvalDatasetTest {

    private static EvalCase base() {
        return new EvalCase("a", "q", "ans", List.of("s1", "s2"), false, List.of("t1", "t2"), Map.of("k", "v"));
    }

    private static EvalDataset dataset(EvalCase... cases) {
        return new EvalDataset("d", List.of(cases));
    }

    @Test
    void goldenFingerprintForMinimalDataset() {
        EvalDataset minimal = new EvalDataset("g", List.of(new EvalCase("a", "q", null, null, false, null, null)));

        assertThat(minimal.fingerprint()).isEqualTo("9e957bd622736d7917110ad4904973ec2127711fcf77c4fc123a4ffff984c1eb");
    }

    @Test
    void validatesNameCasesAndDuplicates() {
        assertThatThrownBy(() -> new EvalDataset("bad name", List.of(base()))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalDataset("d", List.of())).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalDataset("d", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new EvalDataset("d", List.of(base(), base()))).isInstanceOf(ValidationException.class);
    }

    @Test
    void sortsCasesByIdAndCopiesInput() {
        List<EvalCase> input = new ArrayList<>(List.of(
                new EvalCase("b", "q", null, null, false, null, null), new EvalCase("a", "q", null, null, false, null, null)));

        EvalDataset d = new EvalDataset("d", input);
        input.clear();

        assertThat(d.cases()).extracting(EvalCase::id).containsExactly("a", "b");
        assertThatThrownBy(() -> d.cases().add(base())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fingerprintIsStableAndIgnoresInputOrder() {
        EvalCase a = new EvalCase("a", "qa", null, null, false, List.of("y", "x"), Map.of("k2", "2", "k1", "1"));
        EvalCase b = new EvalCase("b", "qb", null, null, false, null, null);

        String first = new EvalDataset("d", List.of(a, b)).fingerprint();

        assertThat(new EvalDataset("d", List.of(a, b)).fingerprint()).isEqualTo(first);
        assertThat(new EvalDataset("d", List.of(b, a)).fingerprint()).isEqualTo(first);
        assertThat(first).hasSize(64).matches("[0-9a-f]+");
    }

    private void assertChanges(Function<EvalCase, EvalCase> change) {
        EvalCase original = base();
        assertThat(dataset(change.apply(original)).fingerprint()).isNotEqualTo(dataset(original).fingerprint());
    }

    @Test
    void fingerprintChangesWhenIdChanges() {
        assertChanges(c -> new EvalCase("z", c.question(), c.expectedAnswer(), c.expectedSources(), false, c.tags(), c.attributes()));
    }

    @Test
    void fingerprintChangesWhenQuestionChanges() {
        assertChanges(c -> new EvalCase(c.id(), "other", c.expectedAnswer(), c.expectedSources(), false, c.tags(), c.attributes()));
    }

    @Test
    void fingerprintChangesWhenExpectedAnswerChanges() {
        assertChanges(c -> new EvalCase(c.id(), c.question(), "other", c.expectedSources(), false, c.tags(), c.attributes()));
        assertChanges(c -> new EvalCase(c.id(), c.question(), null, c.expectedSources(), false, c.tags(), c.attributes()));
        assertChanges(c -> new EvalCase(c.id(), c.question(), "", c.expectedSources(), false, c.tags(), c.attributes()));
    }

    @Test
    void fingerprintChangesWhenSourcesChange() {
        assertChanges(c -> new EvalCase(c.id(), c.question(), c.expectedAnswer(), List.of("s1"), false, c.tags(), c.attributes()));
        assertChanges(c -> new EvalCase(c.id(), c.question(), c.expectedAnswer(), List.of("s2", "s1"), false, c.tags(), c.attributes()));
    }

    @Test
    void fingerprintChangesWhenExpectNoAnswerChanges() {
        EvalCase a = new EvalCase("a", "q", null, null, false, null, null);
        EvalCase b = new EvalCase("a", "q", null, null, true, null, null);

        assertThat(dataset(a).fingerprint()).isNotEqualTo(dataset(b).fingerprint());
    }

    @Test
    void fingerprintChangesWhenTagsChange() {
        assertChanges(c -> new EvalCase(c.id(), c.question(), c.expectedAnswer(), c.expectedSources(), false, List.of("t1"), c.attributes()));
    }

    @Test
    void fingerprintChangesWhenAttributesChange() {
        assertChanges(c -> new EvalCase(c.id(), c.question(), c.expectedAnswer(), c.expectedSources(), false, c.tags(), Map.of("k", "w")));
        assertChanges(c -> new EvalCase(c.id(), c.question(), c.expectedAnswer(), c.expectedSources(), false, c.tags(), Map.of("j", "v")));
    }

    @Test
    void fingerprintChangesWhenNameChanges() {
        assertThat(new EvalDataset("d", List.of(base())).fingerprint())
                .isNotEqualTo(new EvalDataset("e", List.of(base())).fingerprint());
    }

    @Test
    void fieldBoundariesCannotBeConfused() {
        EvalCase one = new EvalCase("a", "bc", "d", null, false, null, null);
        EvalCase two = new EvalCase("a", "b", "cd", null, false, null, null);
        EvalCase tagsOne = new EvalCase("a", "q", null, List.of("ab", "c"), false, null, null);
        EvalCase tagsTwo = new EvalCase("a", "q", null, List.of("a", "bc"), false, null, null);
        EvalCase tagsThree = new EvalCase("a", "q", null, List.of("abc"), false, null, null);

        assertThat(dataset(one).fingerprint()).isNotEqualTo(dataset(two).fingerprint());
        assertThat(dataset(tagsOne).fingerprint()).isNotEqualTo(dataset(tagsTwo).fingerprint());
        assertThat(dataset(tagsTwo).fingerprint()).isNotEqualTo(dataset(tagsThree).fingerprint());
    }
}
