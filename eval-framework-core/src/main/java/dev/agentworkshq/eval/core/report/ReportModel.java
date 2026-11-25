package dev.agentworkshq.eval.core.report;

import dev.agentworkshq.eval.core.compare.CaseChange;
import dev.agentworkshq.eval.core.compare.Comparison;
import dev.agentworkshq.eval.core.compare.ConfigChange;
import dev.agentworkshq.eval.core.compare.MetricAggregate;
import dev.agentworkshq.eval.core.compare.MetricComparison;
import dev.agentworkshq.eval.core.compare.RunSummary;
import dev.agentworkshq.eval.core.model.CaseStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

record ReportModel(
        List<List<String>> runRows,
        Table metrics,
        Boolean gatePassed,
        List<String> violations,
        List<String> notes,
        Table configChanges,
        Table caseChanges,
        int moreCaseChanges,
        Table runConfig) {

    record Table(List<String> headers, List<List<String>> rows) {
    }

    interface Dyn {
        String value(String text, int maxChars, boolean inTable);
    }

    private static final String MISSING = "(missing)";

    static ReportModel build(
            RunSummary summary, Comparison comparison, List<String> gateViolations, ReportOptions options, Dyn dyn) {
        List<String> finalViolations = gateViolations != null
                ? gateViolations
                : comparison != null ? comparison.violations() : null;
        int max = options.maxFieldChars();
        List<List<String>> runRows = new ArrayList<>();
        runRows.add(List.of("Run", dyn.value(summary.runId(), max, true)));
        runRows.add(List.of("Dataset", dyn.value(summary.datasetName(), max, true)));
        runRows.add(List.of("Target", dyn.value(summary.targetName(), max, true)));
        if (comparison != null) {
            runRows.add(List.of("Baseline", dyn.value(comparison.baselineRunId(), max, true)));
        } else if (finalViolations != null) {
            runRows.add(List.of("Baseline", "no baseline"));
        }
        List<String> counts = new ArrayList<>();
        for (CaseStatus status : CaseStatus.values()) {
            counts.add(status.name() + "=" + summary.caseCounts().getOrDefault(status, 0));
        }
        runRows.add(List.of("Cases", String.join(", ", counts)));

        Table metrics = metricTable(summary, comparison, dyn, max);

        Boolean gate = null;
        List<String> violations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Table configChanges = null;
        Table caseChanges = null;
        int more = 0;
        if (finalViolations != null) {
            gate = finalViolations.isEmpty();
            for (String v : finalViolations) {
                violations.add(dyn.value(v, Format.VIOLATION_CHARS, false));
            }
        }
        if (comparison != null) {
            if (comparison.datasetChanged()) {
                notes.add("Dataset content changed.");
            }
            if (comparison.targetChanged()) {
                notes.add("Target changed.");
            }
            if (!comparison.onlyInBaseline().isEmpty()) {
                notes.add("Cases only in the baseline: " + comparison.onlyInBaseline().size());
            }
            if (!comparison.onlyInCandidate().isEmpty()) {
                notes.add("Cases only in the candidate: " + comparison.onlyInCandidate().size());
            }
            if (!comparison.configChanges().isEmpty()) {
                List<List<String>> rows = new ArrayList<>();
                for (ConfigChange c : comparison.configChanges()) {
                    rows.add(List.of(dyn.value(c.key(), max, true), configValue(c.baselineValue(), dyn, max),
                            configValue(c.candidateValue(), dyn, max)));
                }
                configChanges = new Table(List.of("Key", "Baseline", "Candidate"), rows);
            }
            List<CaseChange> ordered = Format.orderedChanges(comparison.caseChanges());
            List<List<String>> rows = new ArrayList<>();
            for (CaseChange c : ordered.subList(0, Math.min(ordered.size(), options.maxCaseRows()))) {
                rows.add(List.of(dyn.value(c.caseId(), max, true),
                        c.metricName() == null ? "(case)" : dyn.value(c.metricName(), max, true), c.kind().name(),
                        Format.statusAndScore(c.baselineStatus(), c.baselineScore()),
                        Format.statusAndScore(c.candidateStatus(), c.candidateScore())));
            }
            more = ordered.size() - rows.size();
            caseChanges = new Table(List.of("Case", "Metric", "Change", "Baseline", "Candidate"), rows);
        }

        Table runConfig = null;
        if (options.includeRunConfig() && !summary.config().isEmpty()) {
            List<List<String>> rows = new ArrayList<>();
            for (Map.Entry<String, String> e : new TreeMap<>(summary.config()).entrySet()) {
                rows.add(List.of(dyn.value(e.getKey(), max, true), dyn.value(e.getValue(), max, true)));
            }
            runConfig = new Table(List.of("Key", "Value"), rows);
        }
        return new ReportModel(runRows, metrics, gate, violations, notes, configChanges, caseChanges, more, runConfig);
    }

    private static String configValue(String value, Dyn dyn, int max) {
        return value == null ? MISSING : dyn.value(value, max, true);
    }

    private static Table metricTable(RunSummary summary, Comparison comparison, Dyn dyn, int max) {
        Map<String, MetricAggregate> fromSummary = new TreeMap<>();
        summary.metrics().forEach(a -> fromSummary.put(a.metricName(), a));
        Map<String, MetricComparison> fromComparison = new TreeMap<>();
        if (comparison != null) {
            comparison.metrics().forEach(m -> fromComparison.put(m.metricName(), m));
        }
        TreeSet<String> names = new TreeSet<>(fromSummary.keySet());
        names.addAll(fromComparison.keySet());

        List<String> headers = new ArrayList<>(List.of("Metric", "Passed", "Failed", "Error", "Skipped", "Mean", "Pass rate"));
        if (comparison != null) {
            headers.addAll(List.of("Baseline mean", "Delta", "Verdict"));
        }
        List<List<String>> rows = new ArrayList<>();
        for (String name : names) {
            MetricAggregate s = fromSummary.get(name);
            List<String> row = new ArrayList<>();
            row.add(dyn.value(name, max, true));
            if (s == null) {
                row.addAll(List.of("-", "-", "-", "-", Format.NA, Format.NA));
            } else {
                row.addAll(List.of(String.valueOf(s.passed()), String.valueOf(s.failed()), String.valueOf(s.error()),
                        String.valueOf(s.skipped()), Format.score(s.meanScore()), Format.rate(s.passRate())));
            }
            if (comparison != null) {
                MetricComparison c = fromComparison.get(name);
                if (c == null) {
                    row.addAll(List.of(Format.NA, Format.NA, "-"));
                } else {
                    Double base = c.baseline() == null ? null : c.baseline().meanScore();
                    Double cand = c.candidate() == null ? null : c.candidate().meanScore();
                    row.addAll(List.of(Format.score(base), Format.delta(base, cand), c.verdict().name()));
                }
            }
            rows.add(row);
        }
        return new Table(headers, rows);
    }
}
