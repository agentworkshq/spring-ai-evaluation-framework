package dev.agentworkshq.eval.core.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JudgeReplyParserTest {

    @Test
    void acceptsAPlainObject() {
        assertThat(JudgeReplyParser.parseScore("{\"score\":3}")).hasValue(3);
    }

    @Test
    void acceptsEveryScoreFromOneToFive() {
        for (int score = 1; score <= 5; score++) {
            assertThat(JudgeReplyParser.parseScore("{\"score\": " + score + "}")).hasValue(score);
        }
    }

    @Test
    void acceptsFencedReplies() {
        assertThat(JudgeReplyParser.parseScore("```json\n{\"score\": 4}\n```")).hasValue(4);
        assertThat(JudgeReplyParser.parseScore("```\n{\"score\": 2}\n```")).hasValue(2);
        assertThat(JudgeReplyParser.parseScore("  ```json\r\n{\"score\": 5}\r\n```  \n")).hasValue(5);
    }

    @Test
    void acceptsExtraKeysAndWhitespace() {
        assertThat(JudgeReplyParser.parseScore("{\"score\":5,\"reason\":\"ignored\",\"nested\":{\"a\":[1]}}")).hasValue(5);
        assertThat(JudgeReplyParser.parseScore("  \n {\"score\" : 1 } \n\t")).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Score: {\"score\":3}",
        "{\"score\":3} thanks",
        "{\"score\":3}\n{\"score\":4}",
        "{\"score\":3}{\"score\":4}",
        "{\"score\":3,\"score\":4}",
        "{\"score\":0}",
        "{\"score\":6}",
        "{\"score\":-1}",
        "{\"score\":4.5}",
        "{\"score\":4.0}",
        "{\"score\":1e0}",
        "{\"score\":\"4\"}",
        "{\"score\":true}",
        "{\"score\":null}",
        "{\"score\":NaN}",
        "{\"score\":99999999999999999999}",
        "{\"score\":[4]}",
        "{\"other\":4}",
        "{}",
        "",
        "   ",
        "{score:3}",
        "{\"score\":3",
        "[3]",
        "3",
        "\"score\"",
        "null",
        "```json\n{\"score\": 4}\n```\nthanks",
        "```json\n```json\n{\"score\": 4}\n```\n```",
        "```json {\"score\": 4}```"
    })
    void rejectsEverythingElse(String reply) {
        assertThat(JudgeReplyParser.parseScore(reply)).isEqualTo(OptionalInt.empty());
    }

    @Test
    void rejectsNull() {
        assertThat(JudgeReplyParser.parseScore(null)).isEmpty();
    }

    @Test
    void rejectsRepliesOverTwoThousandCharacters() {
        String padding = "x".repeat(2000);

        assertThat(JudgeReplyParser.parseScore("{\"score\":3,\"pad\":\"" + padding + "\"}")).isEmpty();
        assertThat(JudgeReplyParser.parseScore("{\"score\":3,\"pad\":\"" + "x".repeat(100) + "\"}")).hasValue(3);
    }
}
