package dev.agentworkshq.eval.core.metric;

import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class MetricFixtures {

    private MetricFixtures() {
    }

    static EvalCase withSources(String... sources) {
        return new EvalCase("c", "question", null, List.of(sources), false, null, null);
    }

    static EvalCase withPhrases(List<String> mustContain, List<String> mustNotContain) {
        return new EvalCase("c", "question", null, null, false, null, null, mustContain, mustNotContain);
    }

    static EvalCase noAnswerCase() {
        return new EvalCase("c", "question", null, null, true, null, null);
    }

    static RetrievedChunk chunk(String source) {
        return new RetrievedChunk("id-" + source, null, source, null, "text", null);
    }

    static RetrievedChunk chunkWithDocument(String documentId) {
        return new RetrievedChunk("id-" + documentId, documentId, null, null, "text", null);
    }

    static RetrievedChunk anonymousChunk() {
        return new RetrievedChunk("anon", null, null, null, "text", null);
    }

    static TargetOutput retrieved(RetrievedChunk... chunks) {
        return new TargetOutput("answer", true, List.of(chunks), List.of(), Duration.ofMillis(10), Map.of());
    }

    static TargetOutput answer(String answer) {
        return new TargetOutput(answer, true, List.of(), List.of(), Duration.ofMillis(10), Map.of());
    }

    static TargetOutput cited(List<RetrievedChunk> chunks, String... cited) {
        return new TargetOutput("answer", true, chunks, List.of(cited), Duration.ofMillis(10), Map.of());
    }
}
