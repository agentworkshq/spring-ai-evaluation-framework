package dev.agentworkshq.eval.core.target;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.util.Map;

public interface EvalTarget {

    String name();

    TargetOutput run(EvalCase evalCase);

    default Map<String, String> describe() {
        return Map.of();
    }
}
