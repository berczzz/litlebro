package com.litlebro.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义文本切块器：基于 embedding 段间相似度寻找语义断点，而非固定大小。
 *
 * <p>借鉴 LangChain SemanticChunker / LlamaIndex SemanticSplitterNodeParser：
 * <ol>
 *   <li>句子切分：按中文句末标点（。！？）与换行切分为候选段，合并过短段</li>
 *   <li>向量化：分批（每批 embedBatchSize 段）调用
 *       {@link EmbeddingModel#embed(java.util.List)}（与检索同一模型，
 *       保证切块向量与检索向量处于同一向量空间）；段数超过上限时跳过向量化，
 *       直接按固定大小切分</li>
 *   <li>相似度序列：buffer 滑动窗口计算相邻窗口的余弦相似度，得差异序列</li>
 *   <li>断点判定：percentile 模式取差异序列第 N 百分位为阈值（默认 95，自适应文档），
 *       fixed 模式用固定相似度阈值</li>
 *   <li>按断点聚合为语义块；块超过 maxChunk 上限时二次按固定大小切分</li>
 * </ol>
 */
public class SemanticTextSplitter extends TextSplitter {

    private static final Logger log = LoggerFactory.getLogger(SemanticTextSplitter.class);

    /** 中文句子切分正则：句末标点 + 换行 */
    private static final Pattern SENTENCE_PATTERN = Pattern.compile("(?<=[。！？；!?;\\n])");

    private final EmbeddingModel embeddingModel;
    private final BreakpointMode breakpointMode;
    private final double percentile;
    private final double fixedThreshold;
    private final int bufferSize;
    private final int maxChunk;
    /** 语义向量化的段数上限，超过则跳过向量化直接按固定大小切分（防大数据表格逐行成段导致海量请求） */
    private final int maxEmbedSegments;
    /** 单次 embedding 请求的输入条数上限（dashscope 兼容模式 text-embedding 系列单请求最多 10 条） */
    private final int embedBatchSize;

    public SemanticTextSplitter(EmbeddingModel embeddingModel, BreakpointMode breakpointMode,
                                double percentile, double fixedThreshold, int bufferSize, int maxChunk,
                                int maxEmbedSegments, int embedBatchSize) {
        this.embeddingModel = embeddingModel;
        this.breakpointMode = breakpointMode;
        this.percentile = percentile;
        this.fixedThreshold = fixedThreshold;
        this.bufferSize = bufferSize;
        this.maxChunk = maxChunk;
        this.maxEmbedSegments = maxEmbedSegments;
        this.embedBatchSize = Math.max(1, embedBatchSize);
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> segments = splitSentences(text);
        if (segments.size() <= 1) {
            return segments;
        }

        // 段数过多（如大数据表格逐行成段）时，逐段向量化会产生海量 embedding 请求，
        // 且此类文本并无语义句子结构，直接按固定大小切分
        if (segments.size() > maxEmbedSegments) {
            log.warn("语义切块段数 {} 超过上限 {}，跳过向量化，按固定大小切分", segments.size(), maxEmbedSegments);
            return forceSplit(text, maxChunk);
        }

        // 分批向量化：每批若干段一次请求，减少 HTTP 调用次数与超时概率
        List<float[]> vectors;
        try {
            vectors = batchEmbed(segments);
        } catch (Exception e) {
            log.warn("句子向量化失败，回退为固定大小切分 原因: {}", e.getMessage());
            return forceSplit(text, maxChunk);
        }

        // buffer 滑动窗口相似度序列
        double[] scores = windowSimilarities(vectors, bufferSize);

        // 断点阈值
        double threshold = breakpointMode == BreakpointMode.PERCENTILE
                ? percentileThreshold(scores, percentile)
                : fixedThreshold;

        // 按断点聚合
        List<String> chunks = buildChunks(segments, scores, threshold);

        // 超限块二次固定切分
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() > maxChunk) {
                result.addAll(forceSplit(chunk, maxChunk));
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    /**
     * 分批向量化：每批若干段一次请求，单批数量受 embedding 接口每请求输入条数上限约束。
     *
     * @param segments 待向量化段列表
     * @return 与输入顺序一致的向量列表
     */
    private List<float[]> batchEmbed(List<String> segments) {
        List<float[]> vectors = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i += embedBatchSize) {
            List<String> batch = segments.subList(i, Math.min(segments.size(), i + embedBatchSize));
            vectors.addAll(embeddingModel.embed(batch));
        }
        return vectors;
    }

    /**
     * 将文本按句子切分为候选段，并合并过短段（避免 embedding 噪声）。
     */
    private List<String> splitSentences(String text) {
        Matcher matcher = SENTENCE_PATTERN.matcher(text);
        int lastEnd = 0;
        List<String> segments = new ArrayList<>();
        while (matcher.find()) {
            segments.add(text.substring(lastEnd, matcher.end()).trim());
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            String tail = text.substring(lastEnd).trim();
            if (!tail.isEmpty()) {
                segments.add(tail);
            }
        }

        // 合并过短段（< 15 字符且不足 bufferSize 的尾部段），降低单句 embedding 噪声
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String seg : segments) {
            if (current.length() == 0) {
                current.append(seg);
            } else if (current.length() < 30 || seg.length() < 15) {
                current.append(seg);
            } else {
                merged.add(current.toString());
                current.setLength(0);
                current.append(seg);
            }
        }
        if (current.length() > 0) {
            merged.add(current.toString());
        }
        return merged;
    }

    /**
     * 计算相邻 buffer 窗口的余弦相似度。窗口内容为前 buffer 个段的向量拼接均值。
     * 相似度越低表示语义跳变越明显，是断点候选位置。
     */
    private double[] windowSimilarities(List<float[]> vectors, int buffer) {
        int n = vectors.size();
        double[] sims = new double[Math.max(0, n - 1)];
        if (sims.length == 0) {
            return sims;
        }
        for (int i = 0; i < sims.length; i++) {
            float[] left = windowVector(vectors, i, buffer);
            float[] right = windowVector(vectors, i + 1, buffer);
            sims[i] = cosine(left, right);
        }
        return sims;
    }

    /** 取以 index 为起点、长度为 buffer 的窗口向量（均值），不足则用可用部分 */
    private float[] windowVector(List<float[]> vectors, int index, int buffer) {
        int end = Math.min(vectors.size(), index + buffer);
        float[] result = new float[vectors.get(0).length];
        for (int i = index; i < end; i++) {
            float[] v = vectors.get(i);
            for (int j = 0; j < result.length; j++) {
                result[j] += v[j];
            }
        }
        int count = end - index;
        for (int j = 0; j < result.length; j++) {
            result[j] /= count;
        }
        return result;
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 取相似度序列第 percentile 百分位值（0-100）。 */
    private double percentileThreshold(double[] scores, double p) {
        if (scores.length == 0) {
            return 0;
        }
        double[] sorted = scores.clone();
        java.util.Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(sorted.length - 1, idx));
        return sorted[idx];
    }

    /** 按断点聚合段为语义块：相似度低于阈值处断句。 */
    private List<String> buildChunks(List<String> segments, double[] scores, double threshold) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder(segments.get(0));
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] < threshold) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(segments.get(i + 1));
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** 超长块按固定字符数二次切分（保证不超过 embedding/上下文上限）。 */
    private List<String> forceSplit(String text, int maxLen) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i += maxLen) {
            result.add(text.substring(i, Math.min(text.length(), i + maxLen)));
        }
        return result;
    }

    /** 断点判定模式。 */
    public enum BreakpointMode {
        PERCENTILE,
        FIXED
    }
}
