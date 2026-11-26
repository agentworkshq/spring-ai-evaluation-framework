package dev.agentworkshq.eval.core.store;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ServiceException;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.MetricStatus;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class RunCodec {

    static final int FORMAT_VERSION = 1;

    private static final Set<String> RUN_FIELDS = Set.of("formatVersion", "runId", "datasetName", "datasetFingerprint",
            "targetName", "startedAt", "finishedAt", "config", "cases");
    private static final Set<String> CASE_FIELDS = Set.of("caseId", "status", "errorMessage", "durationMs", "answered",
            "answerChars", "latencyMs", "retrievedCount", "metricResults");
    private static final Set<String> METRIC_FIELDS = Set.of("metricName", "status", "score", "reason");

    private final StoreLimits limits;
    private final ObjectMapper mapper;

    RunCodec(StoreLimits limits) {
        this.limits = limits;
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(limits.maxNestingDepth())
                .maxDocumentLength(limits.maxFileBytes())
                .maxStringLength(limits.maxFileBytes())
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build();
        this.mapper = JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .build();
    }

    byte[] write(StoredRun run) {
        ObjectNode root = mapper.createObjectNode();
        root.put("formatVersion", FORMAT_VERSION);
        root.put("runId", run.runId());
        root.put("datasetName", run.datasetName());
        root.put("datasetFingerprint", run.datasetFingerprint());
        root.put("targetName", run.targetName());
        root.put("startedAt", run.startedAt().toString());
        root.put("finishedAt", run.finishedAt().toString());
        ObjectNode config = root.putObject("config");
        run.config().forEach(config::put);
        ArrayNode cases = root.putArray("cases");
        for (StoredCase c : run.cases()) {
            ObjectNode node = cases.addObject();
            node.put("caseId", c.caseId());
            node.put("status", c.status().name());
            putString(node, "errorMessage", c.errorMessage());
            node.put("durationMs", c.durationMs());
            if (c.answered() == null) {
                node.putNull("answered");
            } else {
                node.put("answered", c.answered().booleanValue());
            }
            putNumber(node, "answerChars", c.answerChars());
            putNumber(node, "latencyMs", c.latencyMs());
            putNumber(node, "retrievedCount", c.retrievedCount());
            ArrayNode results = node.putArray("metricResults");
            for (MetricResult r : c.metricResults()) {
                ObjectNode result = results.addObject();
                result.put("metricName", r.metricName());
                result.put("status", r.status().name());
                if (r.score() == null) {
                    result.putNull("score");
                } else {
                    result.put("score", r.score().doubleValue());
                }
                putString(result, "reason", r.reason());
            }
        }
        String json;
        try {
            json = mapper.writer().with(printer()).writeValueAsString(root) + "\n";
        } catch (JacksonException e) {
            throw new ServiceException(ErrorMessages.EVAL_E023, e, "write");
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limits.maxFileBytes()) {
            throw new ValidationException(ErrorMessages.EVAL_E019, "run " + run.runId(),
                    ": larger than " + limits.maxFileBytes() + " bytes");
        }
        return bytes;
    }

    StoredRun read(byte[] bytes, String file) {
        if (bytes.length > limits.maxFileBytes()) {
            throw invalid(file, ": larger than " + limits.maxFileBytes() + " bytes");
        }
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            throw invalid(file, ": invalid UTF-8");
        }
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JacksonException e) {
            throw malformed(file, e.getLocation());
        }
        if (root == null || !root.isObject()) {
            throw invalid(file, ": the top level must be an object");
        }
        try {
            return build(root, file);
        } catch (JacksonException e) {
            // the Jackson message and cause repeat the value, so neither is kept
            throw invalid(file, ": a number is out of range");
        } catch (ValidationException e) {
            if (e.getErrorMessage() == ErrorMessages.EVAL_E019) {
                throw e;
            }
            throw new ValidationException(ErrorMessages.EVAL_E019, e, file, ": " + e.getMessage());
        }
    }

    private StoredRun build(JsonNode root, String file) {
        JsonNode version = root.get("formatVersion");
        if (version == null || !version.isIntegralNumber() || version.longValue() != FORMAT_VERSION
                || !version.canConvertToInt()) {
            throw invalid(file, ": unsupported or missing formatVersion");
        }
        rejectUnknown(root, RUN_FIELDS, file);
        String runId = string(root, "runId", false, file);
        String datasetName = string(root, "datasetName", false, file);
        String fingerprint = string(root, "datasetFingerprint", false, file);
        String targetName = string(root, "targetName", false, file);
        Instant startedAt = instant(root, "startedAt", file);
        Instant finishedAt = instant(root, "finishedAt", file);
        JsonNode configNode = field(root, "config", file);
        if (!configNode.isObject()) {
            throw invalid(file, ": field 'config' must be an object");
        }
        Map<String, String> config = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : configNode.properties()) {
            if (!e.getValue().isString()) {
                throw invalid(file, ": field 'config' must map strings to strings");
            }
            config.put(e.getKey(), e.getValue().stringValue());
        }
        JsonNode casesNode = field(root, "cases", file);
        if (!casesNode.isArray()) {
            throw invalid(file, ": field 'cases' must be an array");
        }
        List<StoredCase> cases = new ArrayList<>();
        for (JsonNode node : casesNode) {
            cases.add(buildCase(node, file));
        }
        return new StoredRun(runId, datasetName, fingerprint, targetName, startedAt, finishedAt, config, cases);
    }

    private StoredCase buildCase(JsonNode node, String file) {
        if (!node.isObject()) {
            throw invalid(file, ": every case must be an object");
        }
        rejectUnknown(node, CASE_FIELDS, file);
        String caseId = string(node, "caseId", false, file);
        CaseStatus status = enumValue(CaseStatus.class, string(node, "status", false, file), "status", file);
        String errorMessage = string(node, "errorMessage", true, file);
        long durationMs = integral(node, "durationMs", false, file).longValue();
        JsonNode answeredNode = field(node, "answered", file);
        if (!answeredNode.isNull() && !answeredNode.isBoolean()) {
            throw invalid(file, ": field 'answered' must be a boolean or null");
        }
        Boolean answered = answeredNode.isNull() ? null : answeredNode.booleanValue();
        Long answerChars = integral(node, "answerChars", true, file);
        Long latencyMs = integral(node, "latencyMs", true, file);
        Long retrievedCount = integral(node, "retrievedCount", true, file);
        JsonNode resultsNode = field(node, "metricResults", file);
        if (!resultsNode.isArray()) {
            throw invalid(file, ": field 'metricResults' must be an array");
        }
        List<MetricResult> results = new ArrayList<>();
        for (JsonNode r : resultsNode) {
            results.add(buildResult(r, file));
        }
        return new StoredCase(caseId, status, errorMessage, durationMs, answered, toInt(answerChars, "answerChars", file),
                latencyMs, toInt(retrievedCount, "retrievedCount", file), results);
    }

    private MetricResult buildResult(JsonNode node, String file) {
        if (!node.isObject()) {
            throw invalid(file, ": every metric result must be an object");
        }
        rejectUnknown(node, METRIC_FIELDS, file);
        String name = string(node, "metricName", false, file);
        MetricStatus status = enumValue(MetricStatus.class, string(node, "status", false, file), "status", file);
        JsonNode scoreNode = field(node, "score", file);
        if (!scoreNode.isNull() && !scoreNode.isNumber()) {
            throw invalid(file, ": field 'score' must be a number or null");
        }
        Double score = scoreNode.isNull() ? null : scoreNode.doubleValue();
        return new MetricResult(name, status, score, string(node, "reason", true, file));
    }

    private static DefaultPrettyPrinter printer() {
        Separators separators = Separators.createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator("");
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        return new DefaultPrettyPrinter(separators).withObjectIndenter(indenter).withArrayIndenter(indenter);
    }

    private static void putString(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static void putNumber(ObjectNode node, String name, Number value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value.longValue());
        }
    }

    private static JsonNode field(JsonNode node, String name, String file) {
        JsonNode value = node.get(name);
        if (value == null) {
            throw invalid(file, ": field '" + name + "' is required");
        }
        return value;
    }

    private static String string(JsonNode node, String name, boolean nullable, String file) {
        JsonNode value = field(node, name, file);
        if (value.isNull() && nullable) {
            return null;
        }
        if (!value.isString()) {
            throw invalid(file, ": field '" + name + "' must be a string" + (nullable ? " or null" : ""));
        }
        return value.stringValue();
    }

    private static Long integral(JsonNode node, String name, boolean nullable, String file) {
        JsonNode value = field(node, name, file);
        if (value.isNull() && nullable) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(file, ": field '" + name + "' must be an integer" + (nullable ? " or null" : ""));
        }
        return value.longValue();
    }

    private static Integer toInt(Long value, String name, String file) {
        if (value == null) {
            return null;
        }
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw invalid(file, ": field '" + name + "' is out of range");
        }
        return value.intValue();
    }

    private static Instant instant(JsonNode node, String name, String file) {
        try {
            return Instant.parse(string(node, name, false, file));
        } catch (DateTimeParseException e) {
            throw invalid(file, ": field '" + name + "' is not an ISO-8601 instant");
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name, String file) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw invalid(file, ": field '" + name + "' has an unknown value");
        }
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String file) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                throw invalid(file, ": unknown field '" + Checks.printable(name, 64) + "'");
            }
        }
    }

    private static ValidationException invalid(String file, String detail) {
        return new ValidationException(ErrorMessages.EVAL_E019, file, detail);
    }

    private static ValidationException malformed(String file, TokenStreamLocation location) {
        if (location == null || location.getLineNr() <= 0) {
            return invalid(file, " (malformed JSON)");
        }
        String where = "line " + location.getLineNr() + (location.getColumnNr() > 0 ? ", column " + location.getColumnNr() : "");
        return invalid(file, " (malformed JSON, " + where + ")");
    }
}
