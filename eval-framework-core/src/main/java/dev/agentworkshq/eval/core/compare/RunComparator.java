package dev.agentworkshq.eval.core.compare;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.MetricStatus;
import dev.agentworkshq.eval.core.store.StoredCase;
import dev.agentworkshq.eval.core.store.StoredRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class RunComparator {

    private static final double EPSILON = 1e-9;
    private static final List<String> COMPARABILITY_KEYS = List.of("promptVersion", "judge", "repeats");

    private RunComparator() {
    }

    public static Comparison compare(StoredRun baseline, StoredRun candidate, ComparisonConfig config) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(config, "config");
        if (!baseline.datasetName().equals(candidate.datasetName())) {
            throw new ValidationException(ErrorMessages.EVAL_E022, "the runs are for different datasets");
        }
        boolean datasetChanged = !baseline.datasetFingerprint().equals(candidate.datasetFingerprint());
        if (datasetChanged && config.requireSameDataset()) {
            throw new ValidationException(ErrorMessages.EVAL_E022, "the dataset content differs");
        }

        Map<String, StoredCase> baseCases = byId(baseline);
        Map<String, StoredCase> candCases = byId(candidate);
        List<String> onlyInBaseline = new ArrayList<>(baseCases.keySet());
        onlyInBaseline.removeAll(candCases.keySet());
        List<String> onlyInCandidate = new ArrayList<>(candCases.keySet());
        onlyInCandidate.removeAll(baseCases.keySet());
        List<String> common = new ArrayList<>(baseCases.keySet());
        common.retainAll(candCases.keySet());

        List<StoredCase> baseCommon = common.stream().map(baseCases::get).toList();
        List<StoredCase> candCommon = common.stream().map(candCases::get).toList();
        Map<String, MetricAggregate> baseAgg = byName(RunSummary.aggregates(baseCommon));
        Map<String, MetricAggregate> candAgg = byName(RunSummary.aggregates(candCommon));

        Set<String> names = new TreeSet<>(baseAgg.keySet());
        names.addAll(candAgg.keySet());
        List<MetricComparison> metrics = new ArrayList<>();
        Set<String> comparable = new TreeSet<>();
        Map<String, List<String>> differing = new TreeMap<>();
        for (String name : names) {
            MetricAggregate b = baseAgg.get(name);
            MetricAggregate c = candAgg.get(name);
            double tolerance = config.toleranceFor(name);
            MetricVerdict verdict;
            if (c == null) {
                verdict = MetricVerdict.MISSING_IN_CANDIDATE;
            } else if (b == null) {
                verdict = MetricVerdict.NEW_IN_CANDIDATE;
            } else {
                List<String> keys = differingKeys(baseline, candidate, name);
                if (keys.isEmpty()) {
                    comparable.add(name);
                    verdict = verdict(b, c, tolerance);
                } else {
                    differing.put(name, keys);
                    verdict = MetricVerdict.NOT_COMPARABLE;
                }
            }
            metrics.add(new MetricComparison(name, verdict, tolerance, b, c));
        }

        List<CaseChange> changes = new ArrayList<>();
        for (String id : common) {
            caseChanges(baseCases.get(id), candCases.get(id), comparable, config, changes);
        }

        List<String> violations = violations(config, metrics, changes, differing, candAgg, datasetChanged ? List.of() : onlyInBaseline);
        return new Comparison(baseline.runId(), candidate.runId(), datasetChanged,
                !baseline.targetName().equals(candidate.targetName()), configChanges(baseline, candidate),
                onlyInBaseline, onlyInCandidate, metrics, changes, violations);
    }

    private static MetricVerdict verdict(MetricAggregate b, MetricAggregate c, double tolerance) {
        double limit = tolerance + EPSILON;
        boolean regressed = c.error() > b.error()
                || drops(b.meanScore(), c.meanScore(), limit)
                || drops(b.passRate(), c.passRate(), limit);
        if (regressed) {
            return MetricVerdict.REGRESSED;
        }
        boolean improved = drops(c.meanScore(), b.meanScore(), limit) || drops(c.passRate(), b.passRate(), limit);
        return improved ? MetricVerdict.IMPROVED : MetricVerdict.UNCHANGED;
    }

    private static boolean drops(Double from, Double to, double limit) {
        return from != null && to != null && from - to > limit;
    }

    private static void caseChanges(
            StoredCase base, StoredCase cand, Set<String> comparable, ComparisonConfig config, List<CaseChange> out) {
        if (base.status() == CaseStatus.COMPLETED && cand.status() != CaseStatus.COMPLETED) {
            out.add(new CaseChange(base.caseId(), null, ChangeKind.REGRESSION, base.status().name(), null,
                    cand.status().name(), null));
        }
        Map<String, MetricResult> candResults = new TreeMap<>();
        cand.metricResults().forEach(r -> candResults.put(r.metricName(), r));
        for (MetricResult b : base.metricResults()) {
            MetricResult c = candResults.get(b.metricName());
            if (c == null || !comparable.contains(b.metricName())) {
                continue;
            }
            ChangeKind kind = kind(b, c, config.toleranceFor(b.metricName()) + EPSILON);
            if (kind != null) {
                out.add(new CaseChange(base.caseId(), b.metricName(), kind, b.status().name(), b.score(),
                        c.status().name(), c.score()));
            }
        }
    }

    private static ChangeKind kind(MetricResult b, MetricResult c, double limit) {
        MetricStatus from = b.status();
        MetricStatus to = c.status();
        boolean fromScored = from == MetricStatus.PASSED || from == MetricStatus.FAILED;
        boolean toScored = to == MetricStatus.PASSED || to == MetricStatus.FAILED;
        if (from == MetricStatus.PASSED && (to == MetricStatus.FAILED || to == MetricStatus.ERROR)) {
            return ChangeKind.REGRESSION;
        }
        if ((from == MetricStatus.FAILED || from == MetricStatus.ERROR) && to == MetricStatus.PASSED) {
            return ChangeKind.FIX;
        }
        if (fromScored && to == MetricStatus.SKIPPED) {
            return ChangeKind.COVERAGE_LOST;
        }
        if (from == MetricStatus.SKIPPED && toScored) {
            return ChangeKind.COVERAGE_GAINED;
        }
        if (fromScored && toScored) {
            if (drops(b.score(), c.score(), limit)) {
                return ChangeKind.SCORE_DROP;
            }
            if (drops(c.score(), b.score(), limit)) {
                return ChangeKind.SCORE_GAIN;
            }
        }
        return null;
    }

    private static List<String> violations(
            ComparisonConfig config,
            List<MetricComparison> metrics,
            List<CaseChange> changes,
            Map<String, List<String>> differing,
            Map<String, MetricAggregate> candAgg,
            List<String> missingCases) {
        List<String> out = new ArrayList<>();
        long regressions = changes.stream().filter(c -> c.kind() == ChangeKind.REGRESSION).count();
        if (regressions > config.maxCaseRegressions()) {
            out.add("case regressions: " + regressions + " (allowed " + config.maxCaseRegressions() + ")");
        }
        for (MetricComparison m : metrics) {
            if (m.verdict() == MetricVerdict.REGRESSED) {
                out.add("metric " + m.metricName() + " regressed: mean " + num(m.baseline().meanScore()) + " to "
                        + num(m.candidate().meanScore()) + ", pass rate " + num(m.baseline().passRate()) + " to "
                        + num(m.candidate().passRate()) + ", errors " + m.baseline().error() + " to "
                        + m.candidate().error() + ", tolerance " + num(m.tolerance()));
            }
        }
        for (CaseChange c : changes) {
            if (c.kind() == ChangeKind.SCORE_DROP) {
                out.add("score drop: case " + c.caseId() + " metric " + c.metricName() + " " + num(c.baselineScore())
                        + " to " + num(c.candidateScore()));
            }
        }
        config.minPassRates().forEach((name, floor) -> {
            MetricAggregate agg = candAgg.get(name);
            if (agg == null || agg.passRate() == null) {
                out.add("pass rate of metric " + name + " is unavailable, minimum is " + num(floor));
            } else if (agg.passRate() < floor - EPSILON) {
                out.add("pass rate of metric " + name + " is " + num(agg.passRate()) + ", minimum is " + num(floor));
            }
        });
        if (config.failOnIncomparable()) {
            differing.forEach((name, keys) ->
                    out.add("metric " + name + " is not comparable, differs in " + String.join(", ", keys)));
        }
        for (MetricComparison m : metrics) {
            if (m.verdict() == MetricVerdict.MISSING_IN_CANDIDATE) {
                out.add("metric " + m.metricName() + " is missing in the candidate");
            }
        }
        if (config.failOnLostCoverage()) {
            for (CaseChange c : changes) {
                if (c.kind() == ChangeKind.COVERAGE_LOST) {
                    out.add("coverage lost: case " + c.caseId() + " metric " + c.metricName());
                }
            }
            if (!missingCases.isEmpty()) {
                int shown = Math.min(missingCases.size(), 10);
                String more = missingCases.size() > shown ? " and " + (missingCases.size() - shown) + " more" : "";
                out.add("coverage lost: " + missingCases.size() + " cases missing in the candidate: "
                        + String.join(", ", missingCases.subList(0, shown)) + more);
            }
        }
        return out;
    }

    private static List<ConfigChange> configChanges(StoredRun baseline, StoredRun candidate) {
        Set<String> keys = new TreeSet<>(baseline.config().keySet());
        keys.addAll(candidate.config().keySet());
        List<ConfigChange> out = new ArrayList<>();
        for (String key : keys) {
            String b = baseline.config().get(key);
            String c = candidate.config().get(key);
            if (!Objects.equals(b, c) && isSafe(key)) {
                out.add(new ConfigChange(key, b, c));
            }
        }
        return out;
    }

    private static boolean isSafe(String key) {
        try {
            EvalRun.requireSafeConfigKey(key);
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    private static List<String> differingKeys(StoredRun baseline, StoredRun candidate, String metric) {
        List<String> out = new ArrayList<>();
        for (String key : COMPARABILITY_KEYS) {
            String full = "metric." + metric + "." + key;
            if (!Objects.equals(baseline.config().get(full), candidate.config().get(full))) {
                out.add(key);
            }
        }
        return out;
    }

    private static Map<String, StoredCase> byId(StoredRun run) {
        Map<String, StoredCase> map = new TreeMap<>();
        run.cases().forEach(c -> map.put(c.caseId(), c));
        return map;
    }

    private static Map<String, MetricAggregate> byName(List<MetricAggregate> aggregates) {
        Map<String, MetricAggregate> map = new TreeMap<>();
        aggregates.forEach(a -> map.put(a.metricName(), a));
        return map;
    }

    private static String num(Double value) {
        return value == null ? "none" : String.format(Locale.ROOT, "%.4f", value);
    }
}
