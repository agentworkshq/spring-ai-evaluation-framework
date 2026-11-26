package dev.agentworkshq.eval.core.model;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record EvalRun(
        String runId,
        String datasetName,
        String datasetFingerprint,
        String targetName,
        Instant startedAt,
        Instant finishedAt,
        Map<String, String> config,
        List<CaseResult> caseResults) {

    private static final Set<String> SECRET_WORDS = Set.of(
            "key", "secret", "token", "password", "passwd", "authorization", "credential", "credentials", "apikey", "bearer");

    public EvalRun {
        NameRules.require(runId, "runId");
        NameRules.require(datasetName, "datasetName");
        Checks.required(datasetFingerprint, "datasetFingerprint");
        Checks.required(targetName, "targetName");
        if (startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)) {
            throw Checks.bad("fields 'startedAt' and 'finishedAt' must be set and finishedAt must not be before startedAt");
        }
        config = Checks.sortedMap(config, "config", Integer.MAX_VALUE);
        config.keySet().forEach(EvalRun::requireSafeConfigKey);
        caseResults = Checks.list(caseResults, "caseResults", Integer.MAX_VALUE);
        if (new HashSet<>(caseResults.stream().map(CaseResult::caseId).toList()).size() != caseResults.size()) {
            throw Checks.bad("field 'caseResults' has a duplicate case id");
        }
        caseResults = caseResults.stream().sorted(Comparator.comparing(CaseResult::caseId)).toList();
    }

    @Override
    public String toString() {
        return "EvalRun[runId=" + runId + ", datasetName=" + datasetName + ", cases=" + caseResults.size()
                + ", startedAt=" + startedAt + ", finishedAt=" + finishedAt + "]";
    }

    public static void requireSafeConfigKey(String key) {
        if (looksLikeSecret(key)) {
            throw new ValidationException(ErrorMessages.EVAL_E011,
                    "config key '" + Echo.safe(key) + "' looks like a credential and is not allowed");
        }
    }

    private static boolean looksLikeSecret(String key) {
        // lookarounds keep this linear, keys from run files can be very long
        String spaced = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ");
        for (String segment : spaced.split("[^A-Za-z0-9]+")) {
            if (SECRET_WORDS.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
