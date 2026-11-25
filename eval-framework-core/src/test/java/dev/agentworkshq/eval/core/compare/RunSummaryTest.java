package dev.agentworkshq.eval.core.compare;

import static dev.agentworkshq.eval.core.compare.CompareFixtures.done;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.err;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.fail;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.failedTarget;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.pass;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.run;
import static dev.agentworkshq.eval.core.compare.CompareFixtures.skip;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.store.StoredCase;
import dev.agentworkshq.eval.core.store.StoredRun;
import org.junit.jupiter.api.Test;

class RunSummaryTest {

    @Test
    void aggregatesAreExactOnAHandComputedExample() {
        StoredRun run = run("r",
                done("c1", pass("m", 1.0), skip("only_skipped")),
                done("c2", pass("m", 0.5), skip("only_skipped")),
                done("c3", fail("m", 0.25)),
                done("c4", err("m")),
                done("c5", skip("m")),
                failedTarget("c6", "IllegalStateException after 1 attempts"));

        RunSummary summary = RunSummary.of(run);

        assertThat(summary.runId()).isEqualTo("r");
        assertThat(summary.metrics()).extracting(MetricAggregate::metricName).containsExactly("m", "only_skipped");
        MetricAggregate m = summary.metrics().get(0);
        assertThat(m.passed()).isEqualTo(2);
        assertThat(m.failed()).isEqualTo(1);
        assertThat(m.error()).isEqualTo(1);
        assertThat(m.skipped()).isEqualTo(1);
        assertThat(m.meanScore()).isCloseTo(1.75 / 3, within(1e-12));
        assertThat(m.passRate()).isEqualTo(0.5);

        MetricAggregate skipped = summary.metrics().get(1);
        assertThat(skipped.skipped()).isEqualTo(2);
        assertThat(skipped.meanScore()).isNull();
        assertThat(skipped.passRate()).isNull();
    }

    @Test
    void errorOnlyMetricHasNoMeanButAZeroPassRate() {
        MetricAggregate aggregate = RunSummary.of(run("r", done("a", err("m")))).metrics().get(0);

        assertThat(aggregate.meanScore()).isNull();
        assertThat(aggregate.passRate()).isEqualTo(0.0);
    }

    @Test
    void countsCasesPerStatusIncludingZeros() {
        StoredCase skippedBudget = new StoredCase("d", CaseStatus.SKIPPED_BUDGET, null, 0, null, null, null, null, null);
        StoredRun run = run("r", done("a"), done("b"), failedTarget("c", "x"), skippedBudget);

        RunSummary summary = RunSummary.of(run);

        assertThat(summary.caseCounts()).containsEntry(CaseStatus.COMPLETED, 2)
                .containsEntry(CaseStatus.TARGET_FAILED, 1)
                .containsEntry(CaseStatus.TIMED_OUT, 0)
                .containsEntry(CaseStatus.SKIPPED_BUDGET, 1);
        assertThat(summary.metrics()).isEmpty();
    }

    @Test
    void metricsAreSortedByName() {
        RunSummary summary = RunSummary.of(run("r", done("a", pass("zeta", 1), pass("alpha", 1), pass("mid", 1))));

        assertThat(summary.metrics()).extracting(MetricAggregate::metricName).containsExactly("alpha", "mid", "zeta");
    }
}
