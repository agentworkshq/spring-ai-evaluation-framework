package dev.agentworkshq.eval.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agentworkshq.eval.core.testsupport.ToStringCheck;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToStringReflectionTest {

    // class to reason, empty because every public type here that holds text declares its own toString
    private static final Map<Class<?>, String> SAFE = Map.of();

    private final List<Class<?>> types = ToStringCheck.publicTypes(EvalFrameworkAutoConfiguration.class);

    @Test
    void everyPublicTypeThatCanHoldTextDeclaresItsOwnToString() {
        assertThat(types).contains(EvalFrameworkAutoConfiguration.class, EvalFrameworkProperties.class,
                EvalFrameworkProperties.Judge.class);
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
