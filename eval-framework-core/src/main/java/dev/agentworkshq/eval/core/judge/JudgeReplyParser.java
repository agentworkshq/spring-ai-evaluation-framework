package dev.agentworkshq.eval.core.judge;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class JudgeReplyParser {

    private static final int MAX_REPLY = 2000;
    private static final Pattern FENCE = Pattern.compile("^```[A-Za-z]*[ \\t]*\\R(.*?)\\R?```$", Pattern.DOTALL);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    private JudgeReplyParser() {
    }

    public static OptionalInt parseScore(String reply) {
        if (reply == null || reply.length() > MAX_REPLY) {
            return OptionalInt.empty();
        }
        String text = reply.strip();
        Matcher fence = FENCE.matcher(text);
        if (fence.matches()) {
            text = fence.group(1).strip();
        }
        if (text.isEmpty()) {
            return OptionalInt.empty();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (JacksonException e) {
            return OptionalInt.empty();
        }
        if (root == null || !root.isObject()) {
            return OptionalInt.empty();
        }
        JsonNode score = root.get("score");
        if (score == null || !score.isInt() || score.intValue() < 1 || score.intValue() > 5) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(score.intValue());
    }
}
