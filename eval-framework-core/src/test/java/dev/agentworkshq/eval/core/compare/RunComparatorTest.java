package dev.agentworkshq.eval.core.compare;

import static dev.agentworkshq.eval.core.compare.CompareFixtures.done;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.err;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.fail;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.failedTarget;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.pass;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.run;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.runOf;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.skip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.MetricResult;
import dev.agentworkshq.eval.core.store.StoredCase;
import dev.agentworkshq.eval.core.store.StoredRun;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RunComparatorTest {

    private static final ComparisonConfig DEFAULTS = ComparisonConfig.defaults();

    private static Comparison compare(StoredRun baseline, StoredRun candidate) {
        return RunComparator.compare(baseline, candidate, DEFAULTS);
    }

    private static Comparison single(MetricResult baseline, MetricResult candidate, ComparisonConfig config) {
        return RunComparator.compare(run("base", done("a", baseline)), run("cand", done("a", candidate)), config);
    }

    private static MetricVerdict verdict(Comparison comparison, String metric) {
        return comparison.metrics().stream().filter(m -> m.metricName().equals(metric)).findFirst().orElseThrow().verdict();
    }

    @Test
    void identicalRunsHaveNoChangesAndPassTheGate() {
        StoredRun r = run("r", done("a", pass("m", 0.8), fail("n", 0.2)), done("b", err("m"), skip("n")),
                failedTarget("c", "IllegalStateException after 1 attempts"));

        Comparison c = compare(r, r);

        assertThat(c.caseChanges()).isEmpty();
        assertThat(c.violations()).isEmpty();
        assertThat(c.passesGate()).isTrue();
        assertThat(c.configChanges()).isEmpty();
        assertThat(c.onlyInBaseline()).isEmpty();
        assertThat(c.onlyInCandidate()).isEmpty();
        assertThat(c.datasetChanged()).isFalse();
        assertThat(c.targetChanged()).isFalse();
        assertThat(c.metrics()).extracting(MetricComparison::verdict).containsOnly(MetricVerdict.UNCHANGED);
    }

    @Test
    void metricTransitionsProduceTheRightChangeKinds() {
        assertKind(pass("m", 1.0), fail("m", 0.0), ChangeKind.REGRESSION);
        assertKind(pass("m", 1.0), err("m"), ChangeKind.REGRESSION);
        assertKind(fail("m", 0.0), pass("m", 1.0), ChangeKind.FIX);
        assertKind(err("m"), pass("m", 1.0), ChangeKind.FIX);
        assertKind(pass("m", 1.0), pass("m", 0.5), ChangeKind.SCORE_DROP);
        assertKind(pass("m", 0.5), pass("m", 1.0), ChangeKind.SCORE_GAIN);
        assertKind(fail("m", 0.5), fail("m", 0.1), ChangeKind.SCORE_DROP);
        assertKind(pass("m", 1.0), skip("m"), ChangeKind.COVERAGE_LOST);
        assertKind(fail("m", 0.1), skip("m"), ChangeKind.COVERAGE_LOST);
        assertKind(skip("m"), pass("m", 1.0), ChangeKind.COVERAGE_GAINED);
        assertKind(skip("m"), fail("m", 0.1), ChangeKind.COVERAGE_GAINED);
    }

    private static void assertKind(MetricResult baseline, MetricResult candidate, ChangeKind kind) {
        Comparison c = single(baseline, candidate, DEFAULTS);

        assertThat(c.caseChanges()).hasSize(1);
        CaseChange change = c.caseChanges().get(0);
        assertThat(change.kind()).isEqualTo(kind);
        assertThat(change.caseId()).isEqualTo("a");
        assertThat(change.metricName()).isEqualTo("m");
        assertThat(change.baselineStatus()).isEqualTo(baseline.status().name());
        assertThat(change.candidateStatus()).isEqualTo(candidate.status().name());
        assertThat(change.baselineScore()).isEqualTo(baseline.score());
        assertThat(change.candidateScore()).isEqualTo(candidate.score());
    }

    @Test
    void unscoredTransitionsWithoutAKindProduceNoChange() {
        assertThat(single(fail("m", 0.0), err("m"), DEFAULTS).caseChanges()).isEmpty();
        assertThat(single(err("m"), fail("m", 0.0), DEFAULTS).caseChanges()).isEmpty();
        assertThat(single(skip("m"), err("m"), DEFAULTS).caseChanges()).isEmpty();
    }

    @Test
    void aCaseThatStopsCompletingIsARegression() {
        StoredRun base = run("base", done("a", pass("m", 1.0)));
        StoredRun cand = run("cand", failedTarget("a", "IllegalStateException after 1 attempts"));

        Comparison c = compare(base, cand);

        assertThat(c.caseChanges()).containsExactly(
                new CaseChange("a", null, ChangeKind.REGRESSION, "COMPLETED", null, "TARGET_FAILED", null));
        assertThat(c.passesGate()).isFalse();
        assertThat(compare(cand, base).caseChanges()).isEmpty();
    }

    @Test
    void aScoreDropInsideTheToleranceIsNotFlagged() {
        ComparisonConfig tolerant = DEFAULTS.withDefaultTolerance(0.25);

        Comparison inside = single(pass("m", 0.75), pass("m", 0.5), tolerant);
        Comparison beyond = single(pass("m", 0.75), pass("m", 0.4), tolerant);

        assertThat(inside.caseChanges()).isEmpty();
        assertThat(verdict(inside, "m")).isEqualTo(MetricVerdict.UNCHANGED);
        assertThat(inside.passesGate()).isTrue();
        assertThat(beyond.caseChanges()).extracting(CaseChange::kind).containsExactly(ChangeKind.SCORE_DROP);
        assertThat(verdict(beyond, "m")).isEqualTo(MetricVerdict.REGRESSED);
        assertThat(beyond.passesGate()).isFalse();
    }

    @Test
    void aTinyDropIsFlaggedWithZeroToleranceButNotInsideTheEpsilon() {
        assertThat(single(pass("m", 0.8), pass("m", 0.79), DEFAULTS).caseChanges()).hasSize(1);
        assertThat(single(pass("m", 0.8), pass("m", 0.8 - 1e-12), DEFAULTS).caseChanges()).isEmpty();
    }

    @Test
    void aPerMetricToleranceOverridesTheDefault() {
        ComparisonConfig config = DEFAULTS.withMetricTolerance("m", 0.5);
        StoredRun base = run("base", done("a", pass("m", 1.0), pass("n", 1.0)));
        StoredRun cand = run("cand", done("a", pass("m", 0.6), pass("n", 0.6)));

        Comparison c = RunComparator.compare(base, cand, config);

        assertThat(c.caseChanges()).extracting(CaseChange::metricName).containsExactly("n");
        assertThat(verdict(c, "m")).isEqualTo(MetricVerdict.UNCHANGED);
        assertThat(verdict(c, "n")).isEqualTo(MetricVerdict.REGRESSED);
        assertThat(c.metrics()).extracting(MetricComparison::tolerance).containsExactly(0.5, 0.0);
    }

    @Test
    void swappingBaselineAndCandidateTurnsRegressionIntoFix() {
        StoredRun good = run("good", done("a", pass("m", 1.0)));
        StoredRun bad = run("bad", done("a", fail("m", 0.0)));

        assertThat(compare(good, bad).caseChanges()).extracting(CaseChange::kind).containsExactly(ChangeKind.REGRESSION);
        assertThat(compare(bad, good).caseChanges()).extracting(CaseChange::kind).containsExactly(ChangeKind.FIX);
        assertThat(compare(bad, good).passesGate()).isTrue();
        assertThat(verdict(compare(bad, good), "m")).isEqualTo(MetricVerdict.IMPROVED);
    }

    @Test
    void growingErrorsRegressAMetricEvenWhenTheMeanIsTheSame() {
        ComparisonConfig config = DEFAULTS.withMetricTolerance("m", 1.0);
        StoredRun base = run("base", done("a", pass("m", 1.0)), done("b", pass("m", 1.0)));
        StoredRun cand = run("cand", done("a", pass("m", 1.0)), done("b", err("m")));

        Comparison c = RunComparator.compare(base, cand, config);

        MetricComparison m = c.metrics().get(0);
        assertThat(m.baseline().meanScore()).isEqualTo(m.candidate().meanScore());
        assertThat(m.verdict()).isEqualTo(MetricVerdict.REGRESSED);
        assertThat(c.violations()).anyMatch(v -> v.contains("metric m regressed"));
    }

    @Test
    void manySmallDropsInsideTheMeanToleranceStillShowAsCaseDrops() {
        ComparisonConfig config = DEFAULTS.withDefaultTolerance(0.25);
        StoredRun base = run("base", done("a", pass("m", 0.8)), done("b", pass("m", 0.8)),
                done("c", pass("m", 0.8)), done("d", pass("m", 0.8)));
        StoredRun cand = run("cand", done("a", pass("m", 0.5)), done("b", pass("m", 0.5)),
                done("c", pass("m", 0.8)), done("d", pass("m", 0.8)));

        Comparison c = RunComparator.compare(base, cand, config);

        assertThat(verdict(c, "m")).isEqualTo(MetricVerdict.UNCHANGED);
        assertThat(c.caseChanges()).extracting(CaseChange::caseId, CaseChange::kind)
                .containsExactly(tuple("a", ChangeKind.SCORE_DROP),
                        tuple("b", ChangeKind.SCORE_DROP));
        assertThat(c.passesGate()).isFalse();
        assertThat(c.violations()).hasSize(2).allMatch(v -> v.startsWith("score drop: case"));
    }

    @Test
    void aDifferentDatasetFingerprintThrowsByDefault() {
        StoredRun base = runOf("base", "ds", "fp1", "t", done("a"));
        StoredRun cand = runOf("cand", "ds", "fp2", "t", done("a"));

        assertThatThrownBy(() -> compare(base, cand)).isInstanceOf(BaseException.class)
                .extracting(e -> ((BaseException) e).getErrorMessage()).isEqualTo(ErrorMessages.EVAL_E022);
    }

    @Test
    void aDifferentFingerprintCanBeComparedOnTheCommonCases() {
        StoredRun base = runOf("base", "ds", "fp1", "t", done("a", pass("m", 1.0)), done("b", pass("m", 1.0)));
        StoredRun cand = runOf("cand", "ds", "fp2", "t", done("a", pass("m", 1.0)), done("z", fail("m", 0.0)));

        Comparison c = RunComparator.compare(base, cand, DEFAULTS.withRequireSameDataset(false));

        assertThat(c.datasetChanged()).isTrue();
        assertThat(c.onlyInBaseline()).containsExactly("b");
        assertThat(c.onlyInCandidate()).containsExactly("z");
        assertThat(c.caseChanges()).isEmpty();
        assertThat(c.metrics().get(0).baseline().passed()).isEqualTo(1);
        assertThat(c.metrics().get(0).candidate().failed()).isZero();
        assertThat(c.passesGate()).isTrue();
    }

    @Test
    void differentDatasetNamesAlwaysThrow() {
        StoredRun base = runOf("base", "ds1", "fp", "t", done("a"));
        StoredRun cand = runOf("cand", "ds2", "fp", "t", done("a"));

        assertThatThrownBy(() -> compare(base, cand)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> RunComparator.compare(base, cand, DEFAULTS.withRequireSameDataset(false)))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void aDifferentTargetIsAllowedAndReported() {
        StoredRun base = runOf("base", "ds", "fp", "target-1", done("a", pass("m", 1.0)));
        StoredRun cand = runOf("cand", "ds", "fp", "target-2", done("a", pass("m", 1.0)));

        Comparison c = compare(base, cand);

        assertThat(c.targetChanged()).isTrue();
        assertThat(c.passesGate()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"promptVersion", "judge", "repeats"})
    void changedJudgeSettingsMakeAMetricNotComparable(String key) {
        String full = "metric.m." + key;
        StoredRun base = run("base", Map.of(full, "1"), done("a", pass("m", 1.0)), done("b", pass("m", 1.0)));
        StoredRun cand = run("cand", Map.of(full, "2"), done("a", pass("m", 0.1)), done("b", fail("m", 0.0)));

        Comparison c = compare(base, cand);

        assertThat(verdict(c, "m")).isEqualTo(MetricVerdict.NOT_COMPARABLE);
        assertThat(c.caseChanges()).isEmpty();
        assertThat(c.passesGate()).isFalse();
        assertThat(c.violations()).hasSize(1).first().asString().contains("metric m is not comparable").contains(key);
        assertThat(c.configChanges()).containsExactly(new ConfigChange(full, "1", "2"));
        assertThat(RunComparator.compare(base, cand, DEFAULTS.withFailOnIncomparable(false)).passesGate()).isTrue();
    }

    @Test
    void aKeyMissingOnOneSideCountsAsDifferent() {
        StoredRun base = run("base", Map.of("metric.m.judge", "j"), done("a", pass("m", 1.0)));
        StoredRun cand = run("cand", done("a", pass("m", 1.0)));

        assertThat(verdict(compare(base, cand), "m")).isEqualTo(MetricVerdict.NOT_COMPARABLE);
        assertThat(verdict(compare(cand, base), "m")).isEqualTo(MetricVerdict.NOT_COMPARABLE);
    }

    @Test
    void settingsOfOtherMetricsDoNotAffectComparability() {
        StoredRun base = run("base", Map.of("metric.other.judge", "j1"), done("a", pass("m", 1.0)));
        StoredRun cand = run("cand", Map.of("metric.other.judge", "j2"), done("a", pass("m", 1.0)));

        assertThat(verdict(compare(base, cand), "m")).isEqualTo(MetricVerdict.UNCHANGED);
    }

    @Test
    void missingAndNewMetrics() {
        StoredRun base = run("base", done("a", pass("kept", 1.0), pass("gone", 1.0)));
        StoredRun cand = run("cand", done("a", pass("kept", 1.0), pass("fresh", 1.0)));

        Comparison c = compare(base, cand);

        assertThat(verdict(c, "gone")).isEqualTo(MetricVerdict.MISSING_IN_CANDIDATE);
        assertThat(verdict(c, "fresh")).isEqualTo(MetricVerdict.NEW_IN_CANDIDATE);
        assertThat(verdict(c, "kept")).isEqualTo(MetricVerdict.UNCHANGED);
        assertThat(c.caseChanges()).isEmpty();
        assertThat(c.violations()).containsExactly("metric gone is missing in the candidate");
        assertThat(compare(cand, base).violations()).containsExactly("metric fresh is missing in the candidate");
    }

    @Test
    void lostCoverageFailsTheGateUnlessSwitchedOff() {
        StoredRun base = run("base", done("a", pass("m", 1.0)));
        StoredRun cand = run("cand", done("a", skip("m")));

        Comparison c = compare(base, cand);

        assertThat(c.caseChanges()).extracting(CaseChange::kind).containsExactly(ChangeKind.COVERAGE_LOST);
        assertThat(c.passesGate()).isFalse();
        assertThat(c.violations()).containsExactly("coverage lost: case a metric m");
        assertThat(RunComparator.compare(base, cand, DEFAULTS.withFailOnLostCoverage(false)).passesGate()).isTrue();
        assertThat(compare(cand, base).caseChanges()).extracting(CaseChange::kind).containsExactly(ChangeKind.COVERAGE_GAINED);
        assertThat(compare(cand, base).passesGate()).isTrue();
    }

    @Test
    void aMinimumPassRateIsAnAbsoluteFloorOnTheCandidate() {
        StoredRun base = run("base", done("a", pass("m", 1.0)), done("b", pass("m", 1.0)));
        StoredRun cand = run("cand", done("a", pass("m", 1.0)), done("b", fail("m", 0.0)));
        ComparisonConfig loose = DEFAULTS.withMetricTolerance("m", 1.0).withMaxCaseRegressions(1);

        assertThat(RunComparator.compare(base, cand, loose).passesGate()).isTrue();
        assertThat(RunComparator.compare(base, cand, loose.withMinPassRate("m", 0.5)).passesGate()).isTrue();
        Comparison below = RunComparator.compare(base, cand, loose.withMinPassRate("m", 0.9));
        assertThat(below.violations()).containsExactly("pass rate of metric m is 0.5000, minimum is 0.9000");
        assertThat(RunComparator.compare(base, cand, loose.withMinPassRate("missing", 0.1)).violations())
                .containsExactly("pass rate of metric missing is unavailable, minimum is 0.1000");
    }

    @Test
    void maxCaseRegressionsAllowsUpToThatMany() {
        ComparisonConfig config = DEFAULTS.withMetricTolerance("m", 1.0).withMaxCaseRegressions(2);
        StoredRun base = run("base", done("a", pass("m", 1.0)), done("b", pass("m", 1.0)), done("c", pass("m", 1.0)),
                done("d", pass("m", 1.0)));
        StoredRun two = run("two", done("a", fail("m", 0.0)), done("b", fail("m", 0.0)), done("c", pass("m", 1.0)),
                done("d", pass("m", 1.0)));
        StoredRun three = run("three", done("a", fail("m", 0.0)), done("b", fail("m", 0.0)), done("c", fail("m", 0.0)),
                done("d", pass("m", 1.0)));

        assertThat(RunComparator.compare(base, two, config).passesGate()).isTrue();
        Comparison c = RunComparator.compare(base, three, config);
        assertThat(c.passesGate()).isFalse();
        assertThat(c.violations()).containsExactly("case regressions: 3 (allowed 2)");
        assertThat(compare(base, two).passesGate()).isFalse();
    }

    @Test
    void violationsHoldNoCaseText() {
        String marker = "SECRET-MARKER-TEXT";
        StoredRun base = run("base", done("a", pass("m", 1.0)), done("b", pass("m", 1.0)));
        StoredCase failed = new StoredCase("a", CaseStatus.TARGET_FAILED, marker, 1,
                null, null, null, null, null);
        StoredRun cand = run("cand", failed, done("b", MetricResult.failed("m", 0.0, marker)));

        Comparison c = compare(base, cand);

        assertThat(c.violations()).isNotEmpty().noneMatch(v -> v.contains(marker));
        assertThat(c.toString()).doesNotContain(marker);
    }

    @Test
    void comparingTwiceIsEqualAndInputOrderDoesNotMatter() {
        StoredRun base = run("base", done("a", pass("m", 1.0), pass("n", 0.5)), done("b", pass("m", 1.0)),
                done("c", fail("n", 0.1)));
        StoredRun cand = run("cand", done("c", pass("n", 0.9)), done("a", fail("m", 0.0), pass("n", 0.2)),
                done("b", pass("m", 1.0)));
        StoredRun candReversed = run("cand", done("b", pass("m", 1.0)), done("a", pass("n", 0.2), fail("m", 0.0)),
                done("c", pass("n", 0.9)));

        Comparison first = compare(base, cand);

        assertThat(compare(base, cand)).isEqualTo(first);
        assertThat(compare(base, candReversed)).isEqualTo(first);
        assertThat(first.caseChanges()).extracting(CaseChange::caseId, CaseChange::metricName)
                .containsExactly(tuple("a", "m"), tuple("a", "n"),
                        tuple("c", "n"));
        assertThat(first.metrics()).extracting(MetricComparison::metricName).containsExactly("m", "n");
    }

    @Test
    void configChangesShowBothValuesAndSkipEqualOnes() {
        StoredRun base = run("base", Map.of("a", "1", "same", "x", "gone", "g"), done("a"));
        StoredRun cand = run("cand", Map.of("a", "2", "same", "x", "new", "n"), done("a"));

        assertThat(compare(base, cand).configChanges()).containsExactly(
                new ConfigChange("a", "1", "2"), new ConfigChange("gone", "g", null), new ConfigChange("new", null, "n"));
    }

    @Test
    void anImprovedMetricPassesTheGate() {
        StoredRun base = run("base", done("a", pass("m", 0.5)), done("b", fail("m", 0.2)));
        StoredRun cand = run("cand", done("a", pass("m", 0.9)), done("b", pass("m", 0.8)));

        Comparison c = compare(base, cand);

        assertThat(verdict(c, "m")).isEqualTo(MetricVerdict.IMPROVED);
        assertThat(c.caseChanges()).extracting(CaseChange::kind).containsExactly(ChangeKind.SCORE_GAIN, ChangeKind.FIX);
        assertThat(c.passesGate()).isTrue();
    }

    @Test
    void comparisonConfigValidatesItsInput() {
        assertThatThrownBy(() -> DEFAULTS.withDefaultTolerance(1.1)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> DEFAULTS.withDefaultTolerance(-0.1)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> DEFAULTS.withDefaultTolerance(Double.NaN)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> DEFAULTS.withMetricTolerance("m", 2)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> DEFAULTS.withMetricTolerance(" ", 0.1)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> DEFAULTS.withMinPassRate("m", -1)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> DEFAULTS.withMaxCaseRegressions(-1)).isInstanceOf(BaseException.class);
        assertThat(DEFAULTS.defaultTolerance()).isZero();
        assertThat(DEFAULTS.maxCaseRegressions()).isZero();
        assertThat(DEFAULTS.requireSameDataset() && DEFAULTS.failOnIncomparable() && DEFAULTS.failOnLostCoverage()).isTrue();
        assertThat(DEFAULTS.withMetricTolerance("m", 0.5).toleranceFor("m")).isEqualTo(0.5);
        assertThat(DEFAULTS.withMetricTolerance("m", 0.5).toleranceFor("other")).isZero();
        assertThat(DEFAULTS.withMinPassRate("m", 0.5).minPassRates()).hasSize(1);
    }
}
