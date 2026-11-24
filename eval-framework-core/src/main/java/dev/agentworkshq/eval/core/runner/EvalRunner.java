package dev.agentworkshq.eval.core.runner;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ServiceException;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.metric.Metric;
import dev.agentworkshq.eval.core.model.CaseResult;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.EvalDataset;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.model.Text;
import dev.agentworkshq.eval.core.target.EvalTarget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final DateTimeFormatter RUN_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);
    private static final int MAX_RUN_ID_LENGTH = 100;

    private final Clock clock;
    private final long graceMillis;

    public EvalRunner() {
        this(Clock.systemUTC());
    }

    public EvalRunner(Clock clock) {
        this(clock, 1000);
    }

    EvalRunner(Clock clock, long graceMillis) {
        if (clock == null) {
            throw bad("clock must not be null");
        }
        this.clock = clock;
        this.graceMillis = graceMillis;
    }

    public EvalRun run(EvalDataset dataset, EvalTarget target, List<Metric> metrics, RunConfig config) {
        return run(dataset, target, metrics, config, (result, completed, total) -> { });
    }

    public EvalRun run(EvalDataset dataset, EvalTarget target, List<Metric> metrics, RunConfig config,
            RunListener listener) {
        if (dataset == null || target == null || metrics == null || config == null || listener == null) {
            throw bad("dataset, target, metrics, config and listener must not be null");
        }
        if (target.name() == null || target.name().isBlank()) {
            throw bad("target name must not be blank");
        }
        List<Metric> metricList = checkedMetrics(metrics);
        List<EvalCase> selected = dataset.cases().stream().filter(c -> isSelected(c, config)).toList();
        if (selected.isEmpty()) {
            throw new ValidationException(ErrorMessages.EVAL_E014, dataset.name());
        }
        if (selected.size() > config.maxCases()) {
            throw new ValidationException(ErrorMessages.EVAL_E013, selected.size(), config.maxCases());
        }

        Instant startedAt = clock.instant();
        String runId = config.runId() != null ? config.runId() : generateRunId(dataset.name(), startedAt);
        log.info("Evaluation run started: {} of {} cases selected, concurrency {}", selected.size(),
                dataset.cases().size(), config.concurrency());

        TimeBox box = new TimeBox(graceMillis);
        ExecutorService workers = Executors.newFixedThreadPool(config.concurrency(), daemonThreads());
        Execution execution = new Execution(target, metricList, config, listener, box, selected.size());
        Map<String, CaseResult> results = new ConcurrentHashMap<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (EvalCase evalCase : selected) {
                futures.add(workers.submit(() -> results.put(evalCase.id(), execution.handle(evalCase))));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException(ErrorMessages.EVAL_E016, e);
        } catch (ExecutionException e) {
            throw new ServiceException(ErrorMessages.EVAL_E001, e.getCause());
        } finally {
            workers.shutdownNow();
            box.close();
            awaitQuietly(workers);
        }

        List<CaseResult> ordered = selected.stream().map(c -> results.get(c.id())).toList();
        Instant finishedAt = clock.instant();
        if (finishedAt.isBefore(startedAt)) {
            finishedAt = startedAt;
        }
        EvalRun run = new EvalRun(runId, dataset.name(), dataset.fingerprint(), target.name(), startedAt, finishedAt,
                snapshot(config, metricList, dataset, selected.size(), box.abandonedCalls()), ordered);
        log.info("Evaluation run finished: {} cases, {} completed, {} target failures, {} timeouts, {} skipped, {} ms",
                ordered.size(), count(ordered, CaseStatus.COMPLETED), count(ordered, CaseStatus.TARGET_FAILED),
                count(ordered, CaseStatus.TIMED_OUT), count(ordered, CaseStatus.SKIPPED_BUDGET),
                Duration.between(startedAt, finishedAt).toMillis());
        return run;
    }

    private static List<Metric> checkedMetrics(List<Metric> metrics) {
        List<Metric> list = new ArrayList<>();
        for (Metric metric : metrics) {
            if (metric == null || metric.name() == null || metric.name().isBlank()) {
                throw bad("metrics must not contain null entries or blank names");
            }
            list.add(metric);
        }
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new TreeSet<>();
        for (Metric metric : list) {
            if (!seen.add(metric.name())) {
                duplicates.add(metric.name());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new ValidationException(ErrorMessages.EVAL_E015, String.join(", ", duplicates));
        }
        return List.copyOf(list);
    }

    private static boolean isSelected(EvalCase evalCase, RunConfig config) {
        return config.includeTags().isEmpty() || evalCase.tags().stream().anyMatch(config.includeTags()::contains);
    }

    private static String generateRunId(String datasetName, Instant now) {
        String suffix = "-" + RUN_ID_TIME.format(now);
        int room = MAX_RUN_ID_LENGTH - suffix.length();
        return (datasetName.length() > room ? datasetName.substring(0, room) : datasetName) + suffix;
    }

    private static Map<String, String> snapshot(RunConfig config, List<Metric> metrics, EvalDataset dataset,
            int selected, int abandoned) {
        Map<String, String> map = new TreeMap<>();
        map.put("runner.concurrency", String.valueOf(config.concurrency()));
        map.put("runner.perCaseTimeoutMs", String.valueOf(config.perCaseTimeout().toMillis()));
        map.put("runner.perMetricTimeoutMs", String.valueOf(config.perMetricTimeout().toMillis()));
        map.put("runner.totalTimeoutMs", String.valueOf(config.totalTimeout().toMillis()));
        map.put("runner.maxAttempts", String.valueOf(config.maxAttempts()));
        map.put("runner.maxCases", String.valueOf(config.maxCases()));
        map.put("runner.includeTags", String.join(",", config.includeTags()));
        map.put("runner.selectedCases", String.valueOf(selected));
        map.put("runner.abandonedCalls", String.valueOf(abandoned));
        map.put("dataset.caseCount", String.valueOf(dataset.cases().size()));
        map.put("metrics", metrics.stream().map(Metric::name).sorted().collect(Collectors.joining(",")));
        Package pkg = EvalRunner.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        map.put("library.version", version == null ? "unknown" : version);
        config.labels().forEach((key, value) -> map.put("label." + key, value));
        return map;
    }

    private static long count(List<CaseResult> results, CaseStatus status) {
        return results.stream().filter(r -> r.status() == status).count();
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, "eval-runner-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void awaitQuietly(ExecutorService executor) {
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E012, detail);
    }

    private static String className(Throwable error) {
        String name = error.getClass().getSimpleName();
        return name.isEmpty() ? error.getClass().getName() : name;
    }

    private final class Execution {

        private final EvalTarget target;
        private final List<Metric> metrics;
        private final RunConfig config;
        private final RunListener listener;
        private final TimeBox box;
        private final int total;
        private final long deadline;
        private final AtomicInteger completed = new AtomicInteger();

        Execution(EvalTarget target, List<Metric> metrics, RunConfig config, RunListener listener, TimeBox box,
                int total) {
            this.target = target;
            this.metrics = metrics;
            this.config = config;
            this.listener = listener;
            this.box = box;
            this.total = total;
            this.deadline = System.nanoTime() + config.totalTimeout().toNanos();
        }

        CaseResult handle(EvalCase evalCase) {
            CaseResult result = process(evalCase);
            log.debug("Case {} finished with status {}", evalCase.id(), result.status());
            if (!Thread.currentThread().isInterrupted()) {
                notifyListener(result);
            }
            return result;
        }

        private void notifyListener(CaseResult result) {
            try {
                listener.onCaseCompleted(result, completed.incrementAndGet(), total);
            } catch (Exception e) {
                log.debug("Run listener failed with {}", e.getClass().getName());
            }
        }

        private boolean stopped() {
            return System.nanoTime() - deadline >= 0 || box.abandonedCalls() > config.effectiveMaxAbandonedCalls();
        }

        private CaseResult skipped(EvalCase evalCase) {
            return new CaseResult(evalCase.id(), CaseStatus.SKIPPED_BUDGET, null, null, List.of(), Duration.ZERO);
        }

        private CaseResult process(EvalCase evalCase) {
            if (stopped()) {
                return skipped(evalCase);
            }
            Instant start = clock.instant();
            long caseMillis = config.perCaseTimeout().toMillis();
            TargetOutput output = null;
            String failure = null;
            int attempts = 0;
            try {
                while (output == null) {
                    attempts++;
                    TimeBox.Outcome<TargetOutput> outcome = box.run(() -> {
                        TargetOutput value = target.run(evalCase);
                        if (value == null) {
                            throw new IllegalStateException();
                        }
                        return value;
                    }, caseMillis);
                    if (outcome.state() == TimeBox.State.DONE) {
                        output = outcome.value();
                    } else if (outcome.state() == TimeBox.State.TIMED_OUT) {
                        return result(evalCase, CaseStatus.TIMED_OUT, null, "target exceeded " + caseMillis + " ms",
                                List.of(), start);
                    } else {
                        failure = className(outcome.error());
                        if (attempts >= config.maxAttempts() || stopped()) {
                            break;
                        }
                        if (!config.retryDelay().isZero()) {
                            Thread.sleep(config.retryDelay().toMillis());
                        }
                    }
                }
                if (output == null) {
                    String message = failure + " after " + attempts + (attempts == 1 ? " attempt" : " attempts");
                    return result(evalCase, CaseStatus.TARGET_FAILED, null, message, List.of(), start);
                }
                return result(evalCase, CaseStatus.COMPLETED, output, null, evaluate(evalCase, output), start);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return skipped(evalCase);
            }
        }

        private List<MetricResult> evaluate(EvalCase evalCase, TargetOutput output) throws InterruptedException {
            long metricMillis = config.perMetricTimeout().toMillis();
            List<MetricResult> results = new ArrayList<>();
            for (Metric metric : metrics) {
                TimeBox.Outcome<MetricResult> outcome = box.run(() -> metric.evaluate(evalCase, output), metricMillis);
                results.add(switch (outcome.state()) {
                    case TIMED_OUT -> MetricResult.error(metric.name(), "metric timed out");
                    case FAILED -> MetricResult.error(metric.name(), "metric failed with " + className(outcome.error()));
                    case DONE -> checked(metric, outcome.value());
                });
            }
            return results;
        }

        private MetricResult checked(Metric metric, MetricResult value) {
            if (value == null) {
                return MetricResult.error(metric.name(), "metric returned null");
            }
            if (!metric.name().equals(value.metricName())) {
                return MetricResult.error(metric.name(), "metric returned a result with a different name");
            }
            return value;
        }

        private CaseResult result(EvalCase evalCase, CaseStatus status, TargetOutput output, String message,
                List<MetricResult> metricResults, Instant start) {
            Duration elapsed = Duration.between(start, clock.instant());
            return new CaseResult(evalCase.id(), status, output, Text.truncate(message, 500), metricResults,
                    elapsed.isNegative() ? Duration.ZERO : elapsed);
        }
    }
}
