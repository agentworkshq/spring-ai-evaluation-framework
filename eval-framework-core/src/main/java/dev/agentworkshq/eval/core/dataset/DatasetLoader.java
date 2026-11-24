package dev.agentworkshq.eval.core.dataset;

import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ResourceNotFoundException;
import dev.agentworkshq.eval.core.exception.ServiceException;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.EvalDataset;
import dev.agentworkshq.eval.core.model.NameRules;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class DatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoader.class);

    private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:.*", Pattern.DOTALL);
    private static final Pattern SEPARATORS = Pattern.compile("[/\\\\]");
    private static final Set<String> TOP_FIELDS = Set.of("name", "cases");
    private static final Set<String> CASE_FIELDS =
            Set.of("id", "question", "expectedAnswer", "expectedSources", "expectNoAnswer", "tags", "attributes",
                    "mustContain", "mustNotContain");
    private static final String QUOTE_HINT = "; quote the value (for example \"001\")";

    private final Path root;
    private final DatasetLimits limits;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final LoadSettings yamlSettings;

    public DatasetLoader(Path root, DatasetLimits limits) {
        Objects.requireNonNull(root, "root");
        this.limits = Objects.requireNonNull(limits, "limits");
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw ioFailure(e, "dataset root");
        }
        if (!Files.isDirectory(this.root)) {
            throw new ValidationException(ErrorMessages.EVAL_E003, "dataset root is not a directory");
        }

        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(limits.maxNestingDepth())
                .maxDocumentLength(limits.maxFileBytes())
                .maxStringLength(limits.maxFileBytes())
                .build();
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build();
        this.jsonMapper = JsonMapper.builder(jsonFactory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .build();
        this.yamlSettings = LoadSettings.builder()
                .setAllowDuplicateKeys(false)
                .setCodePointLimit(limits.maxFileBytes())
                .build();
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .loadSettings(yamlSettings)
                .build();
        this.yamlMapper = YAMLMapper.builder(yamlFactory)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .build();
    }

    public EvalDataset load(String relativePath) {
        String label = safe(relativePath, 200);
        Path relative = checkRelative(relativePath, label);
        String fileName = relative.getFileName().toString();
        DatasetFormat format = DatasetFormat.fromFileName(fileName);

        Path real;
        try {
            real = root.resolve(relative).toRealPath();
        } catch (IOException e) {
            throw ioFailure(e, label);
        }
        if (!real.startsWith(root) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new ValidationException(ErrorMessages.EVAL_E003, label);
        }

        byte[] bytes;
        try (InputStream in = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
            bytes = readCapped(in, limits.maxFileBytes(), label);
        } catch (IOException e) {
            throw ioFailure(e, label);
        }

        EvalDataset dataset = build(label, format, bytes, format.stripExtension(fileName));
        log.debug("Loaded dataset {} with {} cases", label, dataset.cases().size());
        return dataset;
    }

    public EvalDataset parse(String datasetName, DatasetFormat format, String content) {
        Objects.requireNonNull(datasetName, "datasetName");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(content, "content");
        String label = safe(datasetName, 64);
        if (content.length() > limits.maxFileBytes()) {
            throw new ValidationException(ErrorMessages.EVAL_E005, label, limits.maxFileBytes());
        }
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limits.maxFileBytes()) {
            throw new ValidationException(ErrorMessages.EVAL_E005, label, limits.maxFileBytes());
        }
        EvalDataset dataset = build(label, format, bytes, datasetName);
        log.debug("Parsed dataset {} with {} cases", label, dataset.cases().size());
        return dataset;
    }

    private static BaseException ioFailure(IOException e, String label) {
        if (e instanceof NoSuchFileException || e instanceof FileNotFoundException) {
            return new ResourceNotFoundException(ErrorMessages.EVAL_E002, e, label);
        }
        return new ServiceException(ErrorMessages.EVAL_E009, e, label);
    }

    static String safe(String text, int max) {
        if (text == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && i < max; i++) {
            char c = text.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        if (text.length() > max) {
            sb.append("...");
        }
        return sb.toString();
    }

    static byte[] readCapped(InputStream in, int cap, String label) throws IOException {
        byte[] data = in.readNBytes((int) Math.min((long) cap + 1, Integer.MAX_VALUE));
        if (data.length > cap) {
            throw new ValidationException(ErrorMessages.EVAL_E005, label, cap);
        }
        return data;
    }

    private Path checkRelative(String relativePath, String label) {
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf('\0') >= 0
                || DRIVE_LETTER.matcher(relativePath).matches()
                || relativePath.startsWith("/") || relativePath.startsWith("\\")) {
            throw new ValidationException(ErrorMessages.EVAL_E003, label);
        }
        for (String segment : SEPARATORS.split(relativePath)) {
            if (segment.equals("..")) {
                throw new ValidationException(ErrorMessages.EVAL_E003, label);
            }
        }
        Path path;
        try {
            path = root.getFileSystem().getPath(relativePath);
        } catch (InvalidPathException e) {
            throw new ValidationException(ErrorMessages.EVAL_E003, e, label);
        }
        if (path.isAbsolute() || path.getRoot() != null || path.getFileName() == null) {
            throw new ValidationException(ErrorMessages.EVAL_E003, label);
        }
        return path;
    }

    private EvalDataset build(String label, DatasetFormat format, byte[] bytes, String jsonlName) {
        int offset = hasBom(bytes) ? 3 : 0;
        if (format == DatasetFormat.JSONL) {
            if (!NameRules.isValid(jsonlName)) {
                throw invalid(label, "dataset", "field 'name' is not a valid dataset name");
            }
            return new EvalDataset(jsonlName, readJsonl(bytes, offset, label));
        }
        String text;
        try {
            text = decode(bytes, offset, bytes.length - offset);
        } catch (CharacterCodingException e) {
            throw new ValidationException(ErrorMessages.EVAL_E007, e, label, " (invalid UTF-8)");
        }
        return readStructured(format, text, label);
    }

    private EvalDataset readStructured(DatasetFormat format, String text, String label) {
        JsonNode root;
        try {
            if (format == DatasetFormat.YAML) {
                rejectAnchorsAndTooDeep(text, label);
                root = yamlMapper.readTree(text);
            } else {
                root = jsonMapper.readTree(text);
            }
        } catch (JacksonException e) {
            throw malformed(label, e, e.getLocation(), 0);
        }
        if (root == null || !root.isObject()) {
            throw invalid(label, "top level", "must be an object with 'name' and 'cases'");
        }
        rejectUnknown(root, TOP_FIELDS, label, "top level");
        String name = readString(root, "name", true, label, "top level");
        if (!NameRules.isValid(name)) {
            throw invalid(label, "top level", "field 'name' is not a valid dataset name");
        }
        JsonNode cases = root.get("cases");
        if (cases == null || cases.isNull()) {
            throw invalid(label, "top level", "field 'cases' is required");
        }
        if (!cases.isArray()) {
            throw invalid(label, "top level", "field 'cases' must be a list of objects");
        }
        if (cases.isEmpty()) {
            throw invalid(label, "top level", "field 'cases' must not be empty");
        }
        if (cases.size() > limits.maxCases()) {
            throw new ValidationException(ErrorMessages.EVAL_E006, label, limits.maxCases());
        }
        List<EvalCase> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < cases.size(); i++) {
            String where = "case " + (i + 1);
            JsonNode node = cases.get(i);
            if (!node.isObject()) {
                throw invalid(label, where, "must be an object");
            }
            result.add(mapCase(node, label, where, ids));
        }
        return new EvalDataset(name, result);
    }

    private void rejectAnchorsAndTooDeep(String text, String label) {
        int depth = 0;
        try {
            for (Event event : new Parse(yamlSettings).parseString(text)) {
                Event.ID id = event.getEventId();
                if (event instanceof NodeEvent node && (id == Event.ID.Alias || node.getAnchor().isPresent())) {
                    throw malformed(label, null, markLine(event), markColumn(event), "anchors and aliases are not allowed");
                }
                if ((id == Event.ID.MappingStart || id == Event.ID.SequenceStart) && ++depth > limits.maxNestingDepth()) {
                    throw malformed(label, null, markLine(event), markColumn(event), "nesting is too deep");
                }
                if (id == Event.ID.MappingEnd || id == Event.ID.SequenceEnd) {
                    depth--;
                }
            }
        } catch (YamlEngineException e) {
            // Syntax errors are reported by the Jackson pass with the same line and column.
        }
    }

    private static int markLine(Event event) {
        return event.getStartMark().map(m -> m.getLine() + 1).orElse(-1);
    }

    private static int markColumn(Event event) {
        return event.getStartMark().map(m -> m.getColumn() + 1).orElse(-1);
    }

    private List<EvalCase> readJsonl(byte[] bytes, int offset, String label) {
        List<EvalCase> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int start = offset;
        int lineNo = 0;
        while (start < bytes.length) {
            int end = start;
            while (end < bytes.length && bytes[end] != '\n') {
                end++;
            }
            lineNo++;
            int lineEnd = end > start && bytes[end - 1] == '\r' ? end - 1 : end;
            int from = start;
            start = end + 1;
            int length = lineEnd - from;
            if (length > limits.maxLineBytes()) {
                throw new ValidationException(ErrorMessages.EVAL_E005, label + ", line " + lineNo, limits.maxLineBytes());
            }
            if (isBlank(bytes, from, lineEnd)) {
                continue;
            }
            if (result.size() >= limits.maxCases()) {
                throw new ValidationException(ErrorMessages.EVAL_E006, label, limits.maxCases());
            }
            String where = "line " + lineNo;
            JsonNode node;
            try {
                node = jsonMapper.readTree(decode(bytes, from, length));
            } catch (CharacterCodingException e) {
                throw new ValidationException(ErrorMessages.EVAL_E007, e, label, " (line " + lineNo + ", invalid UTF-8)");
            } catch (JacksonException e) {
                throw malformed(label, e, e.getLocation(), lineNo);
            }
            if (node == null || !node.isObject()) {
                throw invalid(label, where, "must be a JSON object");
            }
            result.add(mapCase(node, label, where, ids));
        }
        if (result.isEmpty()) {
            throw invalid(label, "dataset", "file contains no cases");
        }
        return result;
    }

    private EvalCase mapCase(JsonNode node, String label, String where, Set<String> ids) {
        rejectUnknown(node, CASE_FIELDS, label, where);
        String id = readString(node, "id", true, label, where);
        String question = readString(node, "question", true, label, where);
        String expectedAnswer = readString(node, "expectedAnswer", false, label, where);
        List<String> sources = readStringList(node, "expectedSources", label, where);
        boolean expectNoAnswer = readBoolean(node, "expectNoAnswer", label, where);
        List<String> tags = readStringList(node, "tags", label, where);
        Map<String, String> attributes = readStringMap(node, "attributes", label, where);
        List<String> mustContain = readStringList(node, "mustContain", label, where);
        List<String> mustNotContain = readStringList(node, "mustNotContain", label, where);
        EvalCase evalCase;
        try {
            evalCase = new EvalCase(id, question, expectedAnswer, sources, expectNoAnswer, tags, attributes,
                    mustContain, mustNotContain);
        } catch (ValidationException e) {
            String prefix = ErrorMessages.EVAL_E010.format("");
            String detail = e.getMessage().startsWith(prefix) ? e.getMessage().substring(prefix.length()) : e.getMessage();
            throw new ValidationException(ErrorMessages.EVAL_E008, e, label, where + ": " + detail);
        }
        if (!ids.add(id)) {
            throw invalid(label, where, "field 'id' duplicates an earlier case: '" + id + "'");
        }
        return evalCase;
    }

    private void rejectUnknown(JsonNode node, Set<String> allowed, String label, String where) {
        for (String field : node.propertyNames()) {
            if (!allowed.contains(field)) {
                throw invalid(label, where, "unknown field '" + safe(field, 64) + "'");
            }
        }
    }

    private String readString(JsonNode node, String field, boolean required, String label, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                throw invalid(label, where, "field '" + field + "' is required");
            }
            return null;
        }
        if (!value.isString()) {
            throw invalid(label, where, "field '" + field + "' must be a string" + (value.isNumber() ? QUOTE_HINT : ""));
        }
        return value.stringValue();
    }

    private boolean readBoolean(JsonNode node, String field, String label, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        if (!value.isBoolean()) {
            throw invalid(label, where, "field '" + field + "' must be a boolean");
        }
        return value.booleanValue();
    }

    private List<String> readStringList(JsonNode node, String field, String label, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw invalid(label, where, "field '" + field + "' must be a list of strings");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isString()) {
                throw invalid(label, where, "field '" + field + "' must be a list of strings" + (item.isNumber() ? QUOTE_HINT : ""));
            }
            result.add(item.stringValue());
        }
        return result;
    }

    private Map<String, String> readStringMap(JsonNode node, String field, String label, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Map.of();
        }
        if (!value.isObject()) {
            throw invalid(label, where, "field '" + field + "' must be a map of string to string");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : value.properties()) {
            if (!entry.getValue().isString()) {
                throw invalid(label, where, "field '" + field + "' must be a map of string to string"
                        + (entry.getValue().isNumber() ? QUOTE_HINT : ""));
            }
            result.put(entry.getKey(), entry.getValue().stringValue());
        }
        return result;
    }

    private static ValidationException invalid(String label, String where, String detail) {
        return new ValidationException(ErrorMessages.EVAL_E008, label, where + ": " + detail);
    }

    private static ValidationException malformed(String label, JacksonException cause, TokenStreamLocation location, int line) {
        int column = location == null ? -1 : location.getColumnNr();
        int lineNr = line > 0 ? line : location == null ? -1 : location.getLineNr();
        return malformed(label, cause, lineNr, column, "");
    }

    private static ValidationException malformed(
            String label, JacksonException cause, int lineNr, int column, String reason) {
        List<String> parts = new ArrayList<>();
        if (!reason.isEmpty()) {
            parts.add(reason);
        }
        if (lineNr > 0) {
            parts.add("line " + lineNr);
            if (column > 0) {
                parts.add("column " + column);
            }
        }
        String where = parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
        return cause == null
                ? new ValidationException(ErrorMessages.EVAL_E007, label, where)
                : new ValidationException(ErrorMessages.EVAL_E007, cause, label, where);
    }

    private static boolean hasBom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF;
    }

    private static boolean isBlank(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != ' ' && bytes[i] != '\t') {
                return false;
            }
        }
        return true;
    }

    private static String decode(byte[] bytes, int offset, int length) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString();
    }
}
