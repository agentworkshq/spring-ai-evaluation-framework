package dev.agentworkshq.eval.core.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SanitizerTest {

    @Test
    void nullAndEmptyGiveAnEmptyString() {
        assertThat(Sanitizer.plain(null, 10)).isEmpty();
        assertThat(Sanitizer.plain("", 10)).isEmpty();
        assertThat(Sanitizer.plain(" \t\n ", 10)).isEmpty();
    }

    @Test
    void controlCharactersBecomeOneSpace() {
        assertThat(Sanitizer.plain("a\nb\r\nc\td\u0000e\u007ff\u0085g\u009fh", 100)).isEqualTo("a b c d e f g h");
    }

    @Test
    void escapeSequencesLoseTheirEscape() {
        assertThat(Sanitizer.plain("\u001b[31mred\u001b[0m", 100)).isEqualTo("[31mred [0m");
    }

    @ParameterizedTest
    @ValueSource(strings = {"‪", "‫", "‬", "‭", "‮", "⁦", "⁧", "⁨", "⁩",
            "‎", "‏", "​", "‌", "‍", "⁠", "﻿", " ", " "})
    void bidiZeroWidthAndSeparatorsAreReplaced(String special) {
        assertThat(Sanitizer.plain("a" + special + "b", 100)).isEqualTo("a b");
        assertThat(Sanitizer.plain(special + "ab" + special, 100)).isEqualTo("ab");
    }

    @Test
    void runsOfSpacesCollapseAndTheResultIsTrimmed() {
        assertThat(Sanitizer.plain("  a   b \n\n c  ", 100)).isEqualTo("a b c");
    }

    @Test
    void longTextIsCutWithAnEllipsis() {
        String result = Sanitizer.plain("x".repeat(100), 10);

        assertThat(result).isEqualTo("xxxxxxx...").hasSize(10);
        assertThat(Sanitizer.plain("abc", 3)).isEqualTo("abc");
    }

    @Test
    void aSurrogatePairIsNeverSplitAtTheCut() {
        String smile = "😀";
        String text = "abcdef" + smile + smile;

        for (int max = 4; max <= text.length(); max++) {
            String result = Sanitizer.plain(text, max);
            assertThat(result.length()).isLessThanOrEqualTo(max);
            assertThat(result.codePoints().noneMatch(cp -> cp == 0xFFFD)).isTrue();
            for (int i = 0; i < result.length(); i++) {
                if (Character.isHighSurrogate(result.charAt(i))) {
                    assertThat(i + 1).isLessThan(result.length());
                    assertThat(Character.isLowSurrogate(result.charAt(i + 1))).isTrue();
                }
            }
        }
        assertThat(Sanitizer.plain("abc" + smile + "zzz", 7)).isEqualTo("abc...");
    }

    @Test
    void ordinaryUnicodeIsKept() {
        assertThat(Sanitizer.plain("café 日本", 100)).isEqualTo("café 日本");
    }
}
