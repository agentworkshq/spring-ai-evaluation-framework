package dev.agentworkshq.eval.rag;

import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.target.EvalTarget;
import dev.agentworkshq.rag.core.answer.Citation;
import dev.agentworkshq.rag.core.answer.RagAnswer;
import dev.agentworkshq.rag.core.answer.RagContext;
import dev.agentworkshq.rag.core.answer.RagService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class RagEngineTarget implements EvalTarget {

    private final RagAsk ask;
    private final RagTargetOptions options;

    private RagEngineTarget(RagAsk ask, RagTargetOptions options) {
        this.ask = ask;
        this.options = options;
    }

    public static RagEngineTarget of(RagService service, RagTargetOptions options) {
        require(service, "service");
        return of(service::ask, options);
    }

    public static RagEngineTarget of(RagAsk ask, RagTargetOptions options) {
        require(ask, "ask");
        require(options, "options");
        return new RagEngineTarget(ask, options);
    }

    @Override
    public String name() {
        return options.name();
    }

    @Override
    public TargetOutput run(EvalCase evalCase) {
        RagContext context = new RagContext(options.tenantId(), options.sourceNames(), options.topK());
        long start = System.nanoTime();
        RagAnswer answer = ask.ask(evalCase.question(), context);
        Duration latency = Duration.ofNanos(System.nanoTime() - start);

        List<Citation> citations = answer.citations() == null ? List.of() : answer.citations();
        List<RetrievedChunk> retrieved = citations.stream().map(RagEngineTarget::chunk).toList();
        List<String> cited = citations.stream()
                .filter(Citation::used)
                .map(Citation::filename)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return new TargetOutput(Objects.requireNonNullElse(answer.text(), ""), answer.answered(), retrieved, cited,
                latency, Map.of());
    }

    @Override
    public Map<String, String> describe() {
        Map<String, String> map = new TreeMap<>(options.description());
        map.put("engine", "rag-engine");
        map.put("topK", options.topK() == null ? "default" : String.valueOf(options.topK()));
        map.put("sourceFilter", String.valueOf(options.sourceNames().size()));
        return map;
    }

    private static RetrievedChunk chunk(Citation citation) {
        return new RetrievedChunk("citation-" + citation.index(), citation.documentId(), citation.filename(),
                citation.page(), Objects.requireNonNullElse(citation.snippet(), ""), null);
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw new ValidationException(ErrorMessages.EVAL_E010, field + " must not be null");
        }
    }
}
