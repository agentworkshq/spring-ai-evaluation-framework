package dev.agentworkshq.eval.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.dataset.DatasetLimits;
import dev.agentworkshq.eval.core.dataset.DatasetLoader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class EchoTest {

    private static final String HOSTILE = "bad\nname\u001b[31m‮end";
    private static final String CLEANED = "bad name [31m end";

    @Test
    void replacesControlAndBidiCharacters() {
        assertThat(Echo.safe(HOSTILE)).isEqualTo(CLEANED);
    }

    @Test
    void cutsLongValues() {
        assertThat(Echo.safe("x".repeat(5000))).hasSize(64).isEqualTo("x".repeat(61) + "...");
        assertThat(Echo.safe("x".repeat(64))).isEqualTo("x".repeat(64));
    }

    @Test
    void usesAPlaceholderForNull() {
        assertThat(Echo.safe(null)).isEqualTo("(null)");
    }

    @Test
    void configKeyErrorsAreCleanedAndCut() {
        assertThatThrownBy(() -> EvalRun.requireSafeConfigKey("password " + HOSTILE))
                .hasMessageContaining("password " + CLEANED)
                .message().doesNotContain("\n", "\u001b", "‮");
        assertThatThrownBy(() -> EvalRun.requireSafeConfigKey("password." + "x".repeat(5000)))
                .message().hasSizeLessThan(200).contains("...");
    }

    @Test
    void datasetPathErrorsAreCleanedAndCut() {
        DatasetLoader loader = new DatasetLoader(Path.of("."), DatasetLimits.defaults());

        assertThatThrownBy(() -> loader.load("../" + HOSTILE + ".yaml"))
                .hasMessageContaining("../" + CLEANED)
                .message().doesNotContain("\n", "\u001b", "‮");
        assertThatThrownBy(() -> loader.load("../" + "x".repeat(5000) + "end.yaml"))
                .message().hasSizeLessThan(250).contains("...xxx").endsWith("end.yaml");
    }

    @Test
    void keepsAShortPath() {
        assertThat(Echo.path("datasets/support.yaml")).isEqualTo("datasets/support.yaml");
        assertThat(Echo.path("x".repeat(200))).isEqualTo("x".repeat(200));
    }

    @Test
    void keepsTheEndOfALongPath() {
        String path = "/root/" + "d".repeat(476) + "/support-file.yaml";

        assertThat(path).hasSize(500);
        assertThat(Echo.path(path)).hasSize(200).isEqualTo("..." + path.substring(path.length() - 197))
                .endsWith("/support-file.yaml");
    }

    @Test
    void doesNotSplitASurrogatePairInAPath() {
        String path = "a".repeat(10) + "\uD83D\uDE00" + "b".repeat(196);

        assertThat(Echo.path(path)).isEqualTo("..." + "b".repeat(196));
    }

    @Test
    void cleansControlAndBidiCharactersInAPath() {
        assertThat(Echo.path(HOSTILE)).isEqualTo(CLEANED);
    }

    @Test
    void usesAPlaceholderForANullPath() {
        assertThat(Echo.path(null)).isEqualTo("(null)");
    }

    @Test
    void duplicateCaseIdErrorsCutTheId() {
        EvalCase longId = new EvalCase("i".repeat(100), "q", null, null, false, null, null, null, null);

        assertThatThrownBy(() -> new EvalDataset("d", List.of(longId, longId)))
                .hasMessageContaining("i".repeat(61) + "...")
                .message().doesNotContain("i".repeat(62));
    }
}
