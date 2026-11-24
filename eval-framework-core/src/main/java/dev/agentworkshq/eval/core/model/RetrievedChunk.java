package dev.agentworkshq.eval.core.model;

public record RetrievedChunk(String id, String documentId, String source, Integer page, String text, Double score) {

    public RetrievedChunk {
        Checks.required(id, "id");
        if (text == null) {
            throw Checks.bad("field 'text' must not be null");
        }
        if (page != null && page < 0) {
            throw Checks.bad("field 'page' must not be negative");
        }
        if (score != null && !Double.isFinite(score)) {
            throw Checks.bad("field 'score' must be a finite number");
        }
    }
}
