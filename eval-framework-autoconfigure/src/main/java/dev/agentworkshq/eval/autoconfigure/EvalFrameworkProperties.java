package dev.agentworkshq.eval.autoconfigure;

import dev.agentworkshq.eval.core.dataset.DatasetLimits;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.judge.JudgeIdentifier;
import dev.agentworkshq.eval.core.runner.RunConfig;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "eval-framework")
public record EvalFrameworkProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Dataset dataset,
        @DefaultValue Baseline baseline,
        @DefaultValue Store store,
        @DefaultValue Report report,
        @DefaultValue Runner runner,
        @DefaultValue Judge judge) {

    public record Dataset(
            @DefaultValue("src/test/resources/eval-datasets") String root,
            @DefaultValue("5242880") int maxFileBytes,
            @DefaultValue("1000") int maxCases) {

        public Dataset {
            requirePath(root, "eval-framework.dataset.root");
            limits(maxFileBytes, maxCases);
        }

        public DatasetLimits limits() {
            return limits(maxFileBytes, maxCases);
        }

        private static DatasetLimits limits(int maxFileBytes, int maxCases) {
            DatasetLimits defaults = DatasetLimits.defaults();
            try {
                return new DatasetLimits(maxFileBytes, maxCases, defaults.maxLineBytes(), defaults.maxNestingDepth());
            } catch (IllegalArgumentException e) {
                throw bad("eval-framework.dataset.max-file-bytes and max-cases must be positive");
            }
        }
    }

    public record Baseline(@DefaultValue("src/test/resources/eval-baselines") String dir) {

        public Baseline {
            requirePath(dir, "eval-framework.baseline.dir");
        }
    }

    public record Store(@DefaultValue("target/eval-runs") String dir, @DefaultValue("true") boolean enabled) {

        public Store {
            requirePath(dir, "eval-framework.store.dir");
        }
    }

    public record Report(@DefaultValue("target/eval-reports") String dir, @DefaultValue("true") boolean enabled) {

        public Report {
            requirePath(dir, "eval-framework.report.dir");
        }
    }

    public record Runner(
            @DefaultValue("1") int concurrency,
            @DefaultValue("60s") Duration perCaseTimeout,
            @DefaultValue("60s") Duration perMetricTimeout,
            @DefaultValue("30m") Duration totalTimeout,
            @DefaultValue("1") int maxAttempts,
            @DefaultValue("1000") int maxCases,
            @DefaultValue("500") int maxJudgeCalls) {

        public Runner {
            toRunConfig(concurrency, perCaseTimeout, perMetricTimeout, totalTimeout, maxAttempts, maxCases, maxJudgeCalls);
        }

        public RunConfig toRunConfig() {
            return toRunConfig(concurrency, perCaseTimeout, perMetricTimeout, totalTimeout, maxAttempts, maxCases,
                    maxJudgeCalls);
        }

        private static RunConfig toRunConfig(int concurrency, Duration perCaseTimeout, Duration perMetricTimeout,
                Duration totalTimeout, int maxAttempts, int maxCases, int maxJudgeCalls) {
            return RunConfig.defaults()
                    .withConcurrency(concurrency)
                    .withPerCaseTimeout(perCaseTimeout)
                    .withPerMetricTimeout(perMetricTimeout)
                    .withTotalTimeout(totalTimeout)
                    .withMaxAttempts(maxAttempts)
                    .withMaxCases(maxCases)
                    .withMaxJudgeCalls(maxJudgeCalls);
        }
    }

    public record Judge(String identifier) {

        public Judge {
            if (identifier != null) {
                JudgeIdentifier.require(identifier);
            }
        }
    }

    private static void requirePath(String value, String property) {
        if (value == null || value.isBlank()) {
            throw bad(property + " must not be blank");
        }
    }

    private static ValidationException bad(String detail) {
        return new ValidationException(ErrorMessages.EVAL_E011, detail);
    }
}
