package dev.agentworkshq.eval.core.store;

import dev.agentworkshq.eval.core.model.CaseResult;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.NameRules;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record StoredRun(
        String runId,
        String datasetName,
        String datasetFingerprint,
        String targetName,
        Instant startedAt,
        Instant finishedAt,
        Map<String, String> config,
        List<StoredCase> cases) {

    private static final int MAX_CONFIG_VALUE = 200;

    public StoredRun {
        NameRules.require(runId, "runId");
        NameRules.require(datasetName, "datasetName");
        Checks.required(datasetFingerprint, "datasetFingerprint");
        Checks.required(targetName, "targetName");
        if (startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)) {
            throw Checks.bad("fields 'startedAt' and 'finishedAt' must be set and finishedAt must not be before startedAt");
        }
        config = checkedConfig(config);
        if (cases == null) {
            cases = List.of();
        }
        if (cases.stream().anyMatch(c -> c == null)) {
            throw Checks.bad("field 'cases' must not contain null entries");
        }
        if (new HashSet<>(cases.stream().map(StoredCase::caseId).toList()).size() != cases.size()) {
            throw Checks.bad("field 'cases' has a duplicate case id");
        }
        cases = cases.stream().sorted(Comparator.comparing(StoredCase::caseId)).toList();
    }

    public static StoredRun from(EvalRun run) {
        List<StoredCase> cases = new ArrayList<>();
        for (CaseResult result : run.caseResults()) {
            TargetOutput output = result.output();
            cases.add(new StoredCase(
                    result.caseId(),
                    result.status(),
                    result.errorMessage(),
                    result.duration().toMillis(),
                    output == null ? null : output.answered(),
                    output == null ? null : output.answer().length(),
                    output == null ? null : output.latency().toMillis(),
                    output == null ? null : output.retrieved().size(),
                    result.metricResults()));
        }
        return new StoredRun(run.runId(), run.datasetName(), run.datasetFingerprint(), run.targetName(),
                run.startedAt(), run.finishedAt(), run.config(), cases);
    }

    private static Map<String, String> checkedConfig(Map<String, String> config) {
        if (config == null) {
            return Map.of();
        }
        for (Map.Entry<String, String> e : config.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                throw Checks.bad("field 'config' must not contain null keys or values");
            }
            EvalRun.requireSafeConfigKey(e.getKey());
            Checks.maxLength(e.getValue(), "config value", MAX_CONFIG_VALUE);
        }
        return Collections.unmodifiableMap(new TreeMap<>(config));
    }

    @Override
    public String toString() {
        return "StoredRun[runId=" + runId + ", datasetName=" + datasetName + ", cases=" + cases.size() + ", config="
                + config.size() + "]";
    }
}
