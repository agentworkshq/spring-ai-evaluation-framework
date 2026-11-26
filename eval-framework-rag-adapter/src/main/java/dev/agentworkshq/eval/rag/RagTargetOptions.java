package dev.agentworkshq.eval.rag;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.EvalRun;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public record RagTargetOptions(
        String name,
        String tenantId,
        List<String> sourceNames,
        Integer topK,
        Map<String, String> description) {

    private static final Set<String> RESERVED_KEYS = Set.of("engine", "topK", "sourceFilter");

    public RagTargetOptions {
        requireName(name);
        if (tenantId == null || tenantId.isBlank()) {
            throw bad("tenantId must not be blank");
        }
        sourceNames = checkedSourceNames(sourceNames);
        if (topK != null && (topK < 1 || topK > 50)) {
            throw bad("topK must be between 1 and 50");
        }
        description = checkedDescription(description);
    }

    public static RagTargetOptions defaults() {
        return new RagTargetOptions("rag-engine", "default", List.of(), null, Map.of());
    }

    public RagTargetOptions withName(String value) {
        return new RagTargetOptions(value, tenantId, sourceNames, topK, description);
    }

    public RagTargetOptions withTenantId(String value) {
        return new RagTargetOptions(name, value, sourceNames, topK, description);
    }

    public RagTargetOptions withSourceNames(List<String> value) {
        return new RagTargetOptions(name, tenantId, value, topK, description);
    }

    public RagTargetOptions withTopK(Integer value) {
        return new RagTargetOptions(name, tenantId, sourceNames, value, description);
    }

    public RagTargetOptions withDescription(Map<String, String> value) {
        return new RagTargetOptions(name, tenantId, sourceNames, topK, value);
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw bad("name must have 1 to 100 characters");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw bad("name must not contain control characters");
        }
        if (name.contains("://")) {
            throw bad("name must not be a URL");
        }
        EvalRun.requireSafeConfigKey(name);
    }

    private static List<String> checkedSourceNames(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > 20) {
            throw bad("sourceNames has more than 20 entries");
        }
        if (values.stream().anyMatch(v -> v == null || v.isBlank())) {
            throw bad("sourceNames must not contain blank entries");
        }
        return List.copyOf(values);
    }

    private static Map<String, String> checkedDescription(Map<String, String> values) {
        if (values == null) {
            return Map.of();
        }
        if (values.size() > 20) {
            throw bad("description has more than 20 entries");
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) {
                throw bad("description keys must not be blank");
            }
            if (RESERVED_KEYS.contains(key)) {
                throw bad("description key '" + key + "' is reserved");
            }
            EvalRun.requireSafeConfigKey(key);
            if (e.getValue() == null || e.getValue().length() > 200) {
                throw bad("description values must not be null or longer than 200 characters");
            }
        }
        return Collections.unmodifiableMap(new TreeMap<>(values));
    }

    private static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E011, detail);
    }
}
