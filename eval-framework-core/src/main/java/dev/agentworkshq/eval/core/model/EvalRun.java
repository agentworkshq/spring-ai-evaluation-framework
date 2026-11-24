package dev.agentworkshq.eval.core.model;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record EvalRun(
        String runId,
        String datasetName,
        String datasetFingerprint,
        String targetName,
        Instant startedAt,
        Instant finishedAt,
        Map<String, String> config,
        List<CaseResult> caseResults) {

    private static final List<String> SECRET_WORDS = List.of("key", "secret", "token", "password", "authorization");

    public EvalRun {
        NameRules.require(runId, "runId");
        NameRules.require(datasetName, "datasetName");
        Checks.required(datasetFingerprint, "datasetFingerprint");
        Checks.required(targetName, "targetName");
        if (startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)) {
            throw Checks.bad("fields 'startedAt' and 'finishedAt' must be set and finishedAt must not be before startedAt");
        }
        config = Checks.sortedMap(config, "config", Integer.MAX_VALUE);
        for (String key : config.keySet()) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (SECRET_WORDS.stream().anyMatch(lower::contains)) {
                throw new ValidationException(ErrorMessages.EVAL_E011,
                        "config key '" + key.replaceAll("\\p{Cntrl}", "?") + "' looks like a credential and is not allowed");
            }
        }
        caseResults = Checks.list(caseResults, "caseResults", Integer.MAX_VALUE);
        if (new HashSet<>(caseResults.stream().map(CaseResult::caseId).toList()).size() != caseResults.size()) {
            throw Checks.bad("field 'caseResults' has a duplicate case id");
        }
        caseResults = caseResults.stream().sorted(Comparator.comparing(CaseResult::caseId)).toList();
    }
}
