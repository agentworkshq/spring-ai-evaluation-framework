package dev.agentworkshq.eval.core.store;

import java.util.List;
import java.util.Optional;

public interface RunStore {

    void save(StoredRun run);

    StoredRun load(String runId);

    List<RunHeader> list();

    Optional<StoredRun> latest(String datasetName, String targetName);
}
