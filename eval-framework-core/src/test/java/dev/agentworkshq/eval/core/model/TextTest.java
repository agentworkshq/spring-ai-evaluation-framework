package dev.agentworkshq.eval.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.ValidationException;
import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void nullAndShortTextAreReturnedAsIs() {
        assertThat(Text.truncate(null, 10)).isNull();
        assertThat(Text.truncate("abc", 3)).isEqualTo("abc");
        assertThat(Text.truncate("", 0)).isEmpty();
    }

    @Test
    void longTextIsCutWithEllipsisWithinLimit() {
        assertThat(Text.truncate("abcdefghij", 8)).isEqualTo("abcde...");
        assertThat(Text.truncate("x".repeat(600), 500)).hasSize(500).endsWith("...");
    }

    @Test
    void tinyLimitsStillRespectTheLimit() {
        assertThat(Text.truncate("abcdef", 3)).isEqualTo("...");
        assertThat(Text.truncate("abcdef", 2)).isEqualTo("..");
        assertThat(Text.truncate("abcdef", 0)).isEmpty();
    }

    @Test
    void neverSplitsASurrogatePair() {
        String pair = new String(Character.toChars(0x1F600));
        String text = "ab" + pair + pair + "cd";

        String cut = Text.truncate(text, 6);

        assertThat(cut).isEqualTo("ab...");
        assertThat(Text.truncate("a" + pair + "bcdef", 6)).isEqualTo("a" + pair + "...");
    }

    @Test
    void rejectsNegativeLimit() {
        assertThatThrownBy(() -> Text.truncate("a", -1)).isInstanceOf(ValidationException.class);
    }
}
