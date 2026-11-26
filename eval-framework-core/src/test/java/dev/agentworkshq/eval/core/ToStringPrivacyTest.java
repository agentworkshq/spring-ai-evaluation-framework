package dev.agentworkshq.eval.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentworkshq.eval.core.compare.CaseChange;
import dev.agentworkshq.eval.core.compare.ChangeKind;
import dev.agentworkshq.eval.core.compare.Comparison;
import dev.agentworkshq.eval.core.compare.ComparisonConfig;
import dev.agentworkshq.eval.core.compare.ConfigChange;
import dev.agentworkshq.eval.core.compare.MetricAggregate;
import dev.agentworkshq.eval.core.compare.MetricComparison;
import dev.agentworkshq.eval.core.compare.MetricVerdict;
import dev.agentworkshq.eval.core.compare.RunSummary;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.runner.RunConfig;
import dev.agentworkshq.eval.core.store.RunHeader;
import dev.agentworkshq.eval.core.store.StoredCase;
import dev.agentworkshq.eval.core.store.StoredRun;
import dev.agentworkshq.eval.core.suite.Thresholds;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToStringPrivacyTest {

    private static final String MARKER = "SECRET-MARKER-TEXT";
    // config keys that contain the word secret are rejected, so keys carry this part only
    private static final String PART = "MARKER-TEXT";
    private static final Instant T0 = Instant.parse("2026-01-02T03:04:05Z");

    private static MetricResult result() {
        return new MetricResult(MARKER, dev.agentworkshq.eval.core.model.MetricStatus.PASSED, 0.5, MARKER);
    }

    private static StoredCase storedCase() {
        return new StoredCase("case-1", CaseStatus.TARGET_FAILED, MARKER, 7, null, null, null, null, List.of(result()));
    }

    private static MetricAggregate aggregate() {
        return new MetricAggregate(MARKER, 1, 2, 3, 4, 0.5, 0.25);
    }

    private static MetricComparison metricComparison() {
        return new MetricComparison(MARKER, MetricVerdict.REGRESSED, 0.1, aggregate(), aggregate());
    }

    private static CaseChange caseChange() {
        return new CaseChange(MARKER, MARKER, ChangeKind.REGRESSION, MARKER, 1.0, MARKER, 0.0);
    }

    private static ConfigChange configChange() {
        return new ConfigChange(MARKER, MARKER, MARKER);
    }

    private static void assertHidden(Object value) {
        assertThat(value.toString()).startsWith(value.getClass().getSimpleName() + "[").doesNotContain(PART);
    }

    @Test
    void metricResult() {
        assertHidden(result());
        assertThat(result().toString()).contains("PASSED", "0.5");
    }

    @Test
    void storedCaseHidesMessageAndResults() {
        assertHidden(storedCase());
        assertThat(storedCase().toString()).contains("case-1", "TARGET_FAILED", "metricResults=1");
    }

    @Test
    void storedRun() {
        StoredRun run = new StoredRun("run-1", "sample", MARKER, MARKER, T0, T0,
                Map.of("label." + PART, MARKER), List.of(storedCase()));

        assertHidden(run);
        assertThat(run.toString()).contains("run-1", "sample", "cases=1", "config=1");
    }

    @Test
    void runHeader() {
        assertHidden(new RunHeader(MARKER, MARKER, MARKER, T0, 3));
    }

    @Test
    void runConfig() {
        RunConfig config = RunConfig.defaults().withRunId("run-1").withIncludeTags(Set.of(MARKER))
                .withLabels(Map.of(PART, MARKER));

        assertHidden(config);
        assertThat(config.toString()).contains("run-1", "includeTags=1", "labels=1");
    }

    @Test
    void runSummary() {
        RunSummary summary = new RunSummary(MARKER, MARKER, MARKER, Map.of(CaseStatus.COMPLETED, 2),
                List.of(aggregate()), Map.of(MARKER, MARKER));

        assertHidden(summary);
        assertThat(summary.toString()).contains("COMPLETED=2", "metrics=1", "config=1");
    }

    @Test
    void comparison() {
        Comparison comparison = new Comparison(MARKER, MARKER, true, false, List.of(configChange()), List.of(MARKER),
                List.of(MARKER), List.of(metricComparison()), List.of(caseChange()), List.of(MARKER));

        assertHidden(comparison);
        assertThat(comparison.toString()).contains("configChanges=1", "violations=1");
    }

    @Test
    void configChangeHidesKeyAndValues() {
        assertHidden(configChange());
    }

    @Test
    void caseChangeHidesIdsAndStatusText() {
        assertHidden(caseChange());
        assertThat(caseChange().toString()).contains("REGRESSION");
    }

    @Test
    void metricAggregate() {
        assertHidden(aggregate());
        assertThat(aggregate().toString()).contains("passed=1", "skipped=4");
    }

    @Test
    void metricComparisonHidesNames() {
        assertHidden(metricComparison());
        assertThat(metricComparison().toString()).contains("REGRESSED");
    }

    @Test
    void thresholds() {
        Thresholds thresholds = Thresholds.defaults().withMinPassRate(MARKER, 1.0).withMinMeanScore(MARKER, 1.0);

        assertHidden(thresholds);
        assertThat(thresholds.toString()).contains("minPassRate=1", "minMeanScore=1");
    }

    @Test
    void comparisonConfig() {
        ComparisonConfig config = ComparisonConfig.defaults().withMetricTolerance(MARKER, 0.1)
                .withMinPassRate(MARKER, 0.5);

        assertHidden(config);
        assertThat(config.toString()).contains("metricTolerances=1", "minPassRates=1");
    }
}
