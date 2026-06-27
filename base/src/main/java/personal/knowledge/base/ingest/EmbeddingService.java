package personal.knowledge.base.ingest;

import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/** Thin wrapper around the Spring AI {@link EmbeddingModel}. */
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /** Embeds a single piece of text. */
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    /** Embeds a batch of texts in input order. */
    public List<float[]> embed(List<String> texts) {
        return embeddingModel.embed(texts);
    }
}
