package dev.agentworkshq.eval.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownTextTest {

    private static String code(String text) {
        return MarkdownText.code(text, 80, false);
    }

    @Test
    void plainTextGetsOneBacktick() {
        assertThat(code("abc")).isEqualTo("`abc`");
    }

    @Test
    void theFenceIsOneLongerThanTheLongestBacktickRun() {
        assertThat(code("a`b")).isEqualTo("``a`b``");
        assertThat(code("a``b")).isEqualTo("```a``b```");
        assertThat(code("a```b")).isEqualTo("````a```b````");
        assertThat(code("a`b``c")).isEqualTo("```a`b``c```");
    }

    @Test
    void leadingAndTrailingBackticksGetPadding() {
        assertThat(code("`a")).isEqualTo("`` `a ``");
        assertThat(code("a`")).isEqualTo("`` a` ``");
        assertThat(code("`")).isEqualTo("`` ` ``");
        assertThat(code("``a``")).isEqualTo("``` ``a`` ```");
    }

    @Test
    void anEmptyStringIsASingleSpaceSpan() {
        assertThat(code("")).isEqualTo("` `");
        assertThat(code(null)).isEqualTo("` `");
    }

    @Test
    void newlinesAndControlsAreRemoved() {
        assertThat(code("a\nb\u001bc")).isEqualTo("`a b c`");
    }

    @Test
    void pipesAreEscapedOnlyInTableMode() {
        assertThat(MarkdownText.code("a|b", 80, true)).isEqualTo("`a\\|b`");
        assertThat(MarkdownText.code("a|b", 80, false)).isEqualTo("`a|b`");
        assertThat(MarkdownText.code("`|`", 80, true)).isEqualTo("`` `\\|` ``");
    }

    @Test
    void textIsTruncatedBeforeFencing() {
        assertThat(MarkdownText.code("x".repeat(100), 10, false)).isEqualTo("`xxxxxxx...`");
    }

    @Test
    void numbersUseTheRootLocale() {
        assertThat(Format.score(0.5)).isEqualTo("0.500");
        assertThat(Format.score(1.0 / 3)).isEqualTo("0.333");
        assertThat(Format.score(null)).isEqualTo("n/a");
        assertThat(Format.rate(0.5)).isEqualTo("50.0%");
        assertThat(Format.rate(1.0 / 3)).isEqualTo("33.3%");
        assertThat(Format.rate(null)).isEqualTo("n/a");
    }

    @Test
    void deltasAreSignedAndExactZeroIsPlain() {
        assertThat(Format.delta(0.5, 0.55)).isEqualTo("+0.050");
        assertThat(Format.delta(0.5, 0.375)).isEqualTo("-0.125");
        assertThat(Format.delta(0.5, 0.5)).isEqualTo("0.000");
        assertThat(Format.delta(0.5, 0.5 + 1e-9)).isEqualTo("0.000");
        assertThat(Format.delta(null, 0.5)).isEqualTo("n/a");
        assertThat(Format.delta(0.5, null)).isEqualTo("n/a");
    }

    @Test
    void numbersIgnoreTheDefaultLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);

            assertThat(Format.score(0.5)).isEqualTo("0.500");
            assertThat(Format.rate(0.5)).isEqualTo("50.0%");
            assertThat(Format.delta(0.0, 0.25)).isEqualTo("+0.250");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
