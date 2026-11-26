package dev.agentworkshq.eval.core;

import static dev.agentworkshq.eval.core.testsupport.ToStringCheck.declaresToString;
import static dev.agentworkshq.eval.core.testsupport.ToStringCheck.holdsText;
import static org.assertj.core.api.Assertions.assertThat;

import dev.agentworkshq.eval.core.dataset.DatasetLoader;
import dev.agentworkshq.eval.core.judge.ChatClientJudgeModel;
import dev.agentworkshq.eval.core.metric.HitAtKMetric;
import dev.agentworkshq.eval.core.metric.JudgeMetric;
import dev.agentworkshq.eval.core.metric.PrecisionAtKMetric;
import dev.agentworkshq.eval.core.metric.RecallAtKMetric;
import dev.agentworkshq.eval.core.metric.ReciprocalRankMetric;
import dev.agentworkshq.eval.core.metric.RegexMetric;
import dev.agentworkshq.eval.core.metric.SpringAiEvaluatorMetric;
import dev.agentworkshq.eval.core.runner.EvalRunner;
import dev.agentworkshq.eval.core.store.FileRunStore;
import dev.agentworkshq.eval.core.suite.EvalSuite;
import dev.agentworkshq.eval.core.testsupport.ToStringCheck;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToStringReflectionTest {

    private static final String PLAIN_CLASS =
            "a class, not a record: it keeps Object.toString, which prints only the class name and a hash";
    private static final String LIMITS_ONLY = PLAIN_CLASS + "; its record field holds numbers only";

    // every entry must keep Object.toString, a class that starts to override it has to be reviewed
    private static final Map<Class<?>, String> SAFE = Map.ofEntries(
            Map.entry(ChatClientJudgeModel.class, PLAIN_CLASS),
            Map.entry(RegexMetric.class, PLAIN_CLASS),
            Map.entry(SpringAiEvaluatorMetric.class, PLAIN_CLASS),
            Map.entry(JudgeMetric.class, PLAIN_CLASS),
            Map.entry(HitAtKMetric.class, PLAIN_CLASS),
            Map.entry(RecallAtKMetric.class, PLAIN_CLASS),
            Map.entry(PrecisionAtKMetric.class, PLAIN_CLASS),
            Map.entry(ReciprocalRankMetric.class, PLAIN_CLASS),
            Map.entry(DatasetLoader.class, LIMITS_ONLY),
            Map.entry(FileRunStore.class, LIMITS_ONLY),
            Map.entry(EvalSuite.class, LIMITS_ONLY));

    @Test
    void everyPublicTypeThatCanHoldTextDeclaresItsOwnToString() {
        List<Class<?>> types = ToStringCheck.publicTypes(EvalRunner.class);

        assertThat(types).hasSizeGreaterThan(50);
        assertThat(ToStringCheck.unprotected(types, SAFE))
                .as("types that need a toString which prints no free text")
                .isEmpty();
    }

    @Test
    void theAllowListHasNoStaleOrUnsafeEntries() {
        assertThat(ToStringCheck.staleEntries(ToStringCheck.publicTypes(EvalRunner.class), SAFE))
                .as("allow list entries that are no longer needed")
                .isEmpty();
    }

    @Test
    void theCheckTellsGeneratedAndDeclaredToStringApart() {
        assertThat(holdsText(Generated.class)).isTrue();
        assertThat(declaresToString(Generated.class)).isFalse();
        assertThat(declaresToString(Declared.class)).isTrue();
        assertThat(holdsText(Nested.class)).isTrue();
        assertThat(holdsText(Listed.class)).isTrue();
        assertThat(holdsText(Numbers.class)).isFalse();
    }

    record Generated(String text) {
    }

    record Declared(String text) {

        @Override
        public String toString() {
            return "Declared[]";
        }
    }

    record Nested(Generated inner) {
    }

    record Listed(List<String> values) {
    }

    record Numbers(int count, Duration time, List<Integer> values) {
    }
}
