package dev.agentworkshq.eval.core.suite;

import dev.agentworkshq.eval.core.compare.Comparison;
import dev.agentworkshq.eval.core.compare.MetricAggregate;
import dev.agentworkshq.eval.core.compare.RunComparator;
import dev.agentworkshq.eval.core.compare.RunSummary;
import dev.agentworkshq.eval.core.dataset.DatasetLoader;
import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ResourceNotFoundException;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.EvalDataset;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.report.ComparisonJson;
import dev.agentworkshq.eval.core.report.ConsoleReport;
import dev.agentworkshq.eval.core.report.MarkdownReport;
import dev.agentworkshq.eval.core.report.ReportWriter;
import dev.agentworkshq.eval.core.runner.EvalRunner;
import dev.agentworkshq.eval.core.store.FileRunStore;
import dev.agentworkshq.eval.core.store.RunStore;
import dev.agentworkshq.eval.core.store.StoreLimits;
import dev.agentworkshq.eval.core.store.StoredRun;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EvalSuite {

    private static final Logger log = LoggerFactory.getLogger(EvalSuite.class);

    private static final String REPORT_PREFIX = "eval-report-";
    private static final int MAX_FILE_NAME = 100;
    private static final int MAX_ID_PART = MAX_FILE_NAME - REPORT_PREFIX.length() - ".json".length();
    private static final double EPSILON = 1e-9;

    private final DatasetLoader loader;
    private final EvalRunner runner;
    private final RunStore runStore;
    private final Path baselineDir;
    private final StoreLimits limits;
    private final ReportWriter reportWriter;

    public EvalSuite(DatasetLoader loader, EvalRunner runner, RunStore runStore, Path baselineDir, StoreLimits limits,
            ReportWriter reportWriter) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.runStore = runStore;
        this.baselineDir = baselineDir;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.reportWriter = reportWriter;
    }

    public EvalOutcome evaluate(String datasetPath, EvalTarget target, List<Metric> metrics, EvalRequest request) {
        Objects.requireNonNull(request, "request");
        StoredRun baseline = loadBaseline(request.baselineRunId());

        EvalDataset dataset = loader.load(datasetPath);
        EvalRun run = runner.run(dataset, target, metrics, request.runConfig());
        StoredRun stored = StoredRun.from(run);
        if (request.saveRun() && runStore != null) {
            save(stored);
        }

        Comparison comparison = baseline == null ? null : RunComparator.compare(baseline, stored, request.comparison());
        RunSummary summary = RunSummary.of(stored);
        List<String> violations = new ArrayList<>(thresholdViolations(request.thresholds(), summary));
        if (comparison != null) {
            violations.addAll(comparison.violations());
        }
        violations.sort(null);

        String console = ConsoleReport.render(summary, comparison, violations, request.reportOptions());
        String markdown = MarkdownReport.render(summary, comparison, violations, request.reportOptions());
        List<String> files = new ArrayList<>();
        if (request.writeReports() && reportWriter != null) {
            String idPart = run.runId().length() > MAX_ID_PART ? run.runId().substring(0, MAX_ID_PART) : run.runId();
            String markdownName = REPORT_PREFIX + idPart + ".md";
            reportWriter.write(markdownName, markdown);
            files.add(markdownName);
            if (comparison != null) {
                String jsonName = REPORT_PREFIX + idPart + ".json";
                reportWriter.write(jsonName, ComparisonJson.render(withViolations(comparison, violations), request.reportOptions()));
                files.add(jsonName);
            }
        }

        EvalOutcome outcome = new EvalOutcome(run, stored, summary, comparison, request.baselineRunId(), violations,
                console, markdown, files);
        log.info("Evaluation suite finished: run {}, {} cases, {} violations, gate {}", run.runId(),
                stored.cases().size(), violations.size(), outcome.passesGate() ? "PASSED" : "FAILED");
        return outcome;
    }

    private StoredRun loadBaseline(String baselineRunId) {
        if (baselineRunId == null) {
            return null;
        }
        if (baselineDir == null) {
            throw new ValidationException(ErrorMessages.EVAL_E028, "a baselineRunId is set but no baseline directory is configured");
        }
        if (!Files.isDirectory(baselineDir)) {
            throw new ResourceNotFoundException(ErrorMessages.EVAL_E027, baselineDir.toString());
        }
        try {
            return FileRunStore.readOnly(baselineDir, limits).load(baselineRunId);
        } catch (ResourceNotFoundException e) {
            throw new ResourceNotFoundException(ErrorMessages.EVAL_E026, e, baselineRunId);
        }
    }

    private void save(StoredRun stored) {
        try {
            runStore.save(stored);
        } catch (BaseException e) {
            if (e.getErrorMessage() == ErrorMessages.EVAL_E018) {
                throw new ValidationException(ErrorMessages.EVAL_E018, e,
                        stored.runId() + " (use a unique run id, for example one that contains the build number)");
            }
            throw e;
        }
    }

    private static List<String> thresholdViolations(Thresholds thresholds, RunSummary summary) {
        Map<String, MetricAggregate> byName = new java.util.TreeMap<>();
        summary.metrics().forEach(a -> byName.put(a.metricName(), a));
        List<String> out = new ArrayList<>();
        thresholds.minPassRate().forEach((name, min) -> {
            MetricAggregate a = byName.get(name);
            check(out, "pass rate", name, a == null ? null : a.passRate(), min);
        });
        thresholds.minMeanScore().forEach((name, min) -> {
            MetricAggregate a = byName.get(name);
            check(out, "mean score", name, a == null ? null : a.meanScore(), min);
        });
        int incomplete = summary.caseCounts().entrySet().stream()
                .filter(e -> e.getKey() != CaseStatus.COMPLETED).mapToInt(Map.Entry::getValue).sum();
        if (incomplete > thresholds.maxIncompleteCases()) {
            out.add("incomplete cases: " + incomplete + " (allowed " + thresholds.maxIncompleteCases() + ")");
        }
        int errors = summary.metrics().stream().mapToInt(MetricAggregate::error).sum();
        if (errors > thresholds.maxMetricErrors()) {
            out.add("metric errors: " + errors + " (allowed " + thresholds.maxMetricErrors() + ")");
        }
        return out;
    }

    private static void check(List<String> out, String what, String metric, Double actual, double min) {
        if (actual == null) {
            out.add(what + " of metric " + metric + " is unavailable, minimum is " + num(min));
        } else if (actual < min - EPSILON) {
            out.add(what + " of metric " + metric + " is " + num(actual) + ", minimum is " + num(min));
        }
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static Comparison withViolations(Comparison c, List<String> violations) {
        return new Comparison(c.baselineRunId(), c.candidateRunId(), c.datasetChanged(), c.targetChanged(),
                c.configChanges(), c.onlyInBaseline(), c.onlyInCandidate(), c.metrics(), c.caseChanges(), violations);
    }
}
