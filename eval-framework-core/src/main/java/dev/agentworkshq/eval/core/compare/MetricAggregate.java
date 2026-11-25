package dev.agentworkshq.eval.core.compare;

import dev.agentworkshq.eval.core.model.MetricResult;
import java.util.List;

public record MetricAggregate(
        String metricName, int passed, int failed, int error, int skipped, Double meanScore, Double passRate) {

    static MetricAggregate of(String metricName, List<MetricResult> results) {
        int passed = 0;
        int failed = 0;
        int error = 0;
        int skipped = 0;
        double sum = 0;
        for (MetricResult r : results) {
            switch (r.status()) {
                case PASSED -> {
                    passed++;
                    sum += r.score();
                }
                case FAILED -> {
                    failed++;
                    sum += r.score();
                }
                case ERROR -> error++;
                case SKIPPED -> skipped++;
            }
        }
        int scored = passed + failed;
        int counted = scored + error;
        return new MetricAggregate(metricName, passed, failed, error, skipped,
                scored == 0 ? null : sum / scored,
                counted == 0 ? null : (double) passed / counted);
    }
}
