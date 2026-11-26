package dev.agentworkshq.eval.core.compare;

import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.store.StoredCase;
import dev.agentworkshq.eval.core.store.StoredRun;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record RunSummary(
        String runId,
        String datasetName,
        String targetName,
        Map<CaseStatus, Integer> caseCounts,
        List<MetricAggregate> metrics,
        Map<String, String> config) {

    public RunSummary {
        caseCounts = Collections.unmodifiableMap(new EnumMap<>(caseCounts));
        metrics = List.copyOf(metrics);
        config = Collections.unmodifiableMap(new TreeMap<>(config));
    }

    public static RunSummary of(StoredRun run) {
        Map<CaseStatus, Integer> counts = new EnumMap<>(CaseStatus.class);
        for (CaseStatus status : CaseStatus.values()) {
            counts.put(status, 0);
        }
        for (StoredCase c : run.cases()) {
            counts.merge(c.status(), 1, Integer::sum);
        }
        return new RunSummary(run.runId(), run.datasetName(), run.targetName(), counts, aggregates(run.cases()), run.config());
    }

    static List<MetricAggregate> aggregates(Collection<StoredCase> cases) {
        Map<String, List<MetricResult>> byMetric = new TreeMap<>();
        for (StoredCase c : cases) {
            for (MetricResult r : c.metricResults()) {
                byMetric.computeIfAbsent(r.metricName(), k -> new ArrayList<>()).add(r);
            }
        }
        List<MetricAggregate> result = new ArrayList<>();
        byMetric.forEach((name, results) -> result.add(MetricAggregate.of(name, results)));
        return result;
    }

    @Override
    public String toString() {
        return "RunSummary[caseCounts=" + caseCounts + ", metrics=" + metrics.size() + ", config=" + config.size() + "]";
    }
}
