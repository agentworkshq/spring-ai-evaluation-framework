package dev.agentworkshq.eval.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvalFrameworkPropertiesToStringTest {

    private static final String MARKER = "SECRET-MARKER-TEXT";

    @Test
    void neverPrintsPathsOrTheJudgeIdentifier() {
        EvalFrameworkProperties.Dataset dataset = new EvalFrameworkProperties.Dataset(MARKER, 10, 20);
        EvalFrameworkProperties.Baseline baseline = new EvalFrameworkProperties.Baseline(MARKER);
        EvalFrameworkProperties.Store store = new EvalFrameworkProperties.Store(MARKER, true);
        EvalFrameworkProperties.Report report = new EvalFrameworkProperties.Report(MARKER, false);
        // an identifier may not contain the word secret
        EvalFrameworkProperties.Judge judge = new EvalFrameworkProperties.Judge("MARKER-TEXT");
        EvalFrameworkProperties.Runner timing = new EvalFrameworkProperties.Runner(1, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 1, 0);
        EvalFrameworkProperties properties = new EvalFrameworkProperties(true, dataset, baseline, store, report,
                timing, judge);

        assertThat(List.of(properties, dataset, baseline, store, report, judge))
                .allSatisfy(value -> assertThat(value.toString())
                        .startsWith(value.getClass().getSimpleName() + "[")
                        .doesNotContain("MARKER-TEXT"));
        assertThat(properties.toString()).contains("maxCases=20", "configured=true");
    }
}
