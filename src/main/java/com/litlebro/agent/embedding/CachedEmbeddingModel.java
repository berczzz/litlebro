package com.litlebro.agent.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embedding 缓存装饰器：包装原始 EmbeddingModel，对每次 embedding 调用
 * 先查缓存、仅对未命中的文本调用底层模型，再将结果写回缓存。
 *
 * 完全透传底层模型的接口行为，对上游（VectorStore / SemanticTextSplitter）透明。
 * 缓存命中时跳过 HTTP 调用，节省 embedding token 与网络开销。
 */
public class CachedEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(CachedEmbeddingModel.class);

    private final EmbeddingModel delegate;
    private final EmbeddingCache cache;

    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    public CachedEmbeddingModel(EmbeddingModel delegate, EmbeddingCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();

        List<float[]> cached = cache.getAll(texts);
        List<Integer> uncachedIndices = new ArrayList<>();
        for (int i = 0; i < cached.size(); i++) {
            if (cached.get(i) == null) {
                uncachedIndices.add(i);
            }
        }

        if (uncachedIndices.isEmpty()) {
            cacheHits.addAndGet(texts.size());
            log.debug("Embedding cache all hit, count={}", texts.size());
            List<Embedding> embeddings = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                embeddings.add(new Embedding(cached.get(i), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        cacheHits.addAndGet(texts.size() - uncachedIndices.size());
        cacheMisses.addAndGet(uncachedIndices.size());

        List<String> uncachedTexts = uncachedIndices.stream()
                .map(texts::get)
                .toList();

        log.debug("Embedding cache: hit {} miss {}, calling underlying model",
                texts.size() - uncachedIndices.size(), uncachedIndices.size());

        EmbeddingResponse response = delegate.call(
                new EmbeddingRequest(uncachedTexts, request.getOptions()));

        List<float[]> newVectors = new ArrayList<>();
        List<String> newKeys = new ArrayList<>();
        for (int i = 0; i < response.getResults().size(); i++) {
            float[] vec = response.getResults().get(i).getOutput();
            newVectors.add(vec);
            newKeys.add(uncachedTexts.get(i));
        }
        cache.putAll(newKeys, newVectors);

        List<Embedding> allEmbeddings = new ArrayList<>(texts.size());
        int uncachedPtr = 0;
        for (int i = 0; i < texts.size(); i++) {
            if (uncachedPtr < uncachedIndices.size() && uncachedIndices.get(uncachedPtr) == i) {
                allEmbeddings.add(new Embedding(newVectors.get(uncachedPtr), i));
                uncachedPtr++;
            } else {
                allEmbeddings.add(new Embedding(cached.get(i), i));
            }
        }
        return new EmbeddingResponse(allEmbeddings);
    }

    @Override
    public float[] embed(Document document) {
        String text = document.getText();
        float[] cached = cache.get(text);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        cacheMisses.incrementAndGet();
        float[] result = delegate.embed(document);
        cache.put(text, result);
        return result;
    }

    @Override
    public float[] embed(String text) {
        float[] cached = cache.get(text);
        if (cached != null) {
            cacheHits.incrementAndGet();
            return cached;
        }
        cacheMisses.incrementAndGet();
        float[] result = delegate.embed(text);
        cache.put(text, result);
        return result;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> cached = cache.getAll(texts);
        List<Integer> uncachedIndices = new ArrayList<>();
        for (int i = 0; i < cached.size(); i++) {
            if (cached.get(i) == null) {
                uncachedIndices.add(i);
            }
        }

        if (uncachedIndices.isEmpty()) {
            cacheHits.addAndGet(texts.size());
            return cached;
        }

        cacheHits.addAndGet(texts.size() - uncachedIndices.size());
        cacheMisses.addAndGet(uncachedIndices.size());

        List<String> uncachedTexts = uncachedIndices.stream()
                .map(texts::get)
                .toList();

        List<float[]> freshVectors = delegate.embed(uncachedTexts);

        List<String> newKeys = new ArrayList<>();
        List<float[]> newValues = new ArrayList<>();
        for (int i = 0; i < uncachedIndices.size(); i++) {
            newKeys.add(uncachedTexts.get(i));
            newValues.add(freshVectors.get(i));
        }
        cache.putAll(newKeys, newValues);

        List<float[]> result = new ArrayList<>(texts.size());
        int uncachedPtr = 0;
        for (int i = 0; i < texts.size(); i++) {
            if (uncachedPtr < uncachedIndices.size() && uncachedIndices.get(uncachedPtr) == i) {
                result.add(freshVectors.get(uncachedPtr));
                uncachedPtr++;
            } else {
                result.add(cached.get(i));
            }
        }
        return result;
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    public long getCacheHits() {
        return cacheHits.get();
    }

    public long getCacheMisses() {
        return cacheMisses.get();
    }

    public double getHitRate() {
        long total = cacheHits.get() + cacheMisses.get();
        return total == 0 ? 0.0 : (double) cacheHits.get() / total;
    }

    public EmbeddingModel getDelegate() {
        return delegate;
    }
}
