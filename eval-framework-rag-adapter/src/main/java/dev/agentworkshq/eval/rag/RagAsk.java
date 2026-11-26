package dev.agentworkshq.eval.rag;

import dev.agentworkshq.rag.core.answer.RagAnswer;
import dev.agentworkshq.rag.core.answer.RagContext;

@FunctionalInterface
public interface RagAsk {

    RagAnswer ask(String question, RagContext context);
}
