package dev.agentworkshq.eval.rag;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentworkshq.eval.core.testsupport.ToStringCheck;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToStringReflectionTest {

    // every entry must keep Object.toString, a class that starts to override it has to be reviewed
    private static final Map<Class<?>, String> SAFE = Map.of(
            RagEngineTarget.class,
            "a class, not a record: it keeps Object.toString, which does not print the options it holds");

    private final List<Class<?>> types = ToStringCheck.publicTypes(RagEngineTarget.class);

    @Test
    void everyPublicTypeThatCanHoldTextDeclaresItsOwnToString() {
        assertThat(types).contains(RagEngineTarget.class, RagTargetOptions.class);
        assertThat(ToStringCheck.unprotected(types, SAFE))
                .as("types that need a toString which prints no free text")
                .isEmpty();
    }

    @Test
    void theAllowListHasNoStaleOrUnsafeEntries() {
        assertThat(ToStringCheck.staleEntries(types, SAFE))
                .as("allow list entries that are no longer needed")
                .isEmpty();
    }
}
