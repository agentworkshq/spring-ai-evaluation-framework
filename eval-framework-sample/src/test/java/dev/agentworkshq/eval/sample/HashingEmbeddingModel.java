package dev.agentworkshq.eval.sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class HashingEmbeddingModel implements EmbeddingModel {

    static final int DIMENSIONS = 256;

    private static final Set<String> STOP_WORDS = Set.of(
            "about", "all", "and", "any", "are", "but", "can", "could", "does", "for", "from", "had", "has", "have",
            "how", "into", "its", "should", "than", "that", "the", "their", "them", "then", "there", "they", "this",
            "was", "were", "what", "when", "where", "which", "who", "why", "will", "with", "would", "you", "your");

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> result = new ArrayList<>();
        List<String> inputs = request.getInstructions();
        for (int i = 0; i < inputs.size(); i++) {
            result.add(new Embedding(vector(inputs.get(i)), i));
        }
        return new EmbeddingResponse(result);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] vector(String text) {
        float[] vector = new float[DIMENSIONS];
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1.0f;
            }
        }
        double squares = 0;
        for (float value : vector) {
            squares += value * value;
        }
        if (squares > 0) {
            float norm = (float) Math.sqrt(squares);
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }
}
