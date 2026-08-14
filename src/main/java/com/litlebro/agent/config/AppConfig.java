package com.litlebro.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.attachment.AttachmentRegistry;
import com.litlebro.agent.attachment.external.RedisAttachmentRegistry;
import com.litlebro.agent.attachment.local.LocalAttachmentRegistry;
import com.litlebro.agent.common.SystemPrompt;
import com.litlebro.agent.context.CompressionService;
import com.litlebro.agent.memory.LongTermMemoryService;
import com.litlebro.agent.memory.MessageCodec;
import com.litlebro.agent.memory.VectorMemoryStore;
import com.litlebro.agent.memory.external.RedisChatMemory;
import com.litlebro.agent.rag.DocumentParseCache;
import com.litlebro.agent.rag.DocumentSplitterFactory;
import com.litlebro.agent.rag.SemanticTextSplitter;
import com.litlebro.agent.rag.external.RedisDocumentParseCache;
import com.litlebro.agent.rag.local.LocalDocumentParseCache;
import com.litlebro.agent.session.SessionManager;
import com.litlebro.agent.session.external.RedisSessionManager;
import com.litlebro.agent.session.local.LocalSessionManager;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 全局 Bean 装配中心。
 *
 * <p>统一管理各存储后端与业务组件的装配，各存储后端由独立配置项切换，互不影响：
 * <ul>
 *   <li>短期记忆（STM）{@code app.memory.stm.type}：{@code local}（默认）/ {@code redis}</li>
 *   <li>长期记忆（LTM）{@code app.memory.ltm.type}：{@code local}（默认）/ {@code milvus}</li>
 *   <li>文档解析缓存 {@code app.rag.cache.type}：{@code local}（默认）/ {@code redis}</li>
 *   <li>附件注册表 {@code app.attachment.registry.type}：{@code local}（默认）/ {@code redis}</li>
 * </ul>
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AppConfig {

    // ==================== 短期记忆（STM）====================

    @Bean
    @ConditionalOnProperty(name = "app.memory.stm.type", havingValue = "local", matchIfMissing = true)
    public ChatMemory inMemoryChatMemory() {
        return new InMemoryChatMemory();
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.stm.type", havingValue = "redis")
    public RedisTemplate<String, Object> stmRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // 值统一存 JSON 字符串：RedisChatMemory 存 List<AgentMessage>，SessionManager 存 SessionMemory
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.stm.type", havingValue = "redis")
    public ChatMemory redisChatMemory(RedisTemplate<String, Object> stmRedisTemplate, ObjectMapper objectMapper,
                                      MessageCodec messageCodec) {
        return new RedisChatMemory(stmRedisTemplate, objectMapper, messageCodec);
    }

    // ==================== 长期记忆（LTM）====================

    @Bean
    @ConditionalOnProperty(name = "app.memory.ltm.type", havingValue = "local", matchIfMissing = true)
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.ltm.type", havingValue = "milvus")
    public MilvusServiceClient milvusClient(
            @Value("${spring.ai.vectorstore.milvus.client.host:localhost}") String host,
            @Value("${spring.ai.vectorstore.milvus.client.port:19530}") int port,
            @Value("${spring.ai.vectorstore.milvus.client.username:}") String username,
            @Value("${spring.ai.vectorstore.milvus.client.password:}") String password) {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port);
        if (username != null && !username.isEmpty()) {
            builder.withAuthorization(username, password);
        }
        return new MilvusServiceClient(builder.build());
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.ltm.type", havingValue = "milvus")
    public VectorStore milvusVectorStore(MilvusServiceClient milvusClient,
                                         EmbeddingModel embeddingModel,
                                         @Value("${spring.ai.vectorstore.milvus.database-name:default}") String databaseName,
                                         @Value("${spring.ai.vectorstore.milvus.collection-name:vector_store}") String collectionName,
                                         @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1536}") int embeddingDimension,
                                         @Value("${spring.ai.vectorstore.milvus.index-type:IVF_FLAT}") String indexType,
                                         @Value("${spring.ai.vectorstore.milvus.metric-type:COSINE}") String metricType) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .databaseName(databaseName)
                .collectionName(collectionName)
                .embeddingDimension(embeddingDimension)
                .indexType(IndexType.valueOf(indexType))
                .metricType(MetricType.valueOf(metricType))
                .initializeSchema(true)
                .build();
    }

    // ==================== 会话状态 ====================

    @Bean
    @ConditionalOnProperty(name = "app.memory.stm.type", havingValue = "local", matchIfMissing = true)
    public SessionManager localSessionManager() {
        return new LocalSessionManager();
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.stm.type", havingValue = "redis")
    public SessionManager redisSessionManager(
            @Qualifier("stmRedisTemplate") RedisTemplate<String, Object> stmRedisTemplate,
            ObjectMapper objectMapper) {
        return new RedisSessionManager(stmRedisTemplate, objectMapper);
    }

    // ==================== 文档解析缓存 ====================

    /**
     * 文档解析缓存存储由 {@code app.rag.cache.type} 显式决定：
     * {@code redis} 时写入 Redis（TTL {@code app.rag.cache.parse-ttl-hours}），
     * {@code local}（默认）时写入本地内存。与短期记忆配置互不影响。
     */
    @Bean
    @ConditionalOnProperty(name = "app.rag.cache.type", havingValue = "redis")
    public RedisTemplate<String, Object> ragCacheRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // 值统一存 JSON 字符串：解析文本直接以字符串存储
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.cache.type", havingValue = "local", matchIfMissing = true)
    public DocumentParseCache localDocumentParseCache() {
        return new LocalDocumentParseCache();
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.cache.type", havingValue = "redis")
    public DocumentParseCache redisDocumentParseCache(
            RedisTemplate<String, Object> ragCacheRedisTemplate,
            @Value("${app.rag.cache.parse-ttl-hours:24}") long ttlHours) {
        return new RedisDocumentParseCache(ragCacheRedisTemplate, ttlHours);
    }

    // ==================== 附件注册表 ====================

    /**
     * 附件注册表存储由 {@code app.attachment.registry.type} 显式决定：
     * {@code redis} 时写入 Redis（TTL 与附件过期时间对齐），重启不丢；
     * {@code local}（默认）时写入本地内存，重启丢失。与短期记忆配置互不影响。
     */
    @Bean
    @ConditionalOnProperty(name = "app.attachment.registry.type", havingValue = "redis")
    public RedisTemplate<String, Object> attachmentRegistryRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // 值统一存 JSON 字符串：附件条目以 JSON 存储，索引集合存 fileId 字符串
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnProperty(name = "app.attachment.registry.type", havingValue = "local", matchIfMissing = true)
    public AttachmentRegistry localAttachmentRegistry() {
        return new LocalAttachmentRegistry();
    }

    @Bean
    @ConditionalOnProperty(name = "app.attachment.registry.type", havingValue = "redis")
    public AttachmentRegistry redisAttachmentRegistry(
            @Qualifier("attachmentRegistryRedisTemplate") RedisTemplate<String, Object> attachmentRegistryRedisTemplate,
            ObjectMapper objectMapper) {
        return new RedisAttachmentRegistry(attachmentRegistryRedisTemplate, objectMapper);
    }

    // ==================== 业务装配 ====================

    @Bean
    public VectorMemoryStore vectorMemoryStore(
            VectorStore vectorStore,
            @Value("${app.memory.vector.similarity-threshold:0.2}") double similarityThreshold) {
        return new VectorMemoryStore(vectorStore, similarityThreshold);
    }

    @Bean
    public CompressionService compressionService(ChatClient chatClient) {
        return new CompressionService(chatClient);
    }

    @Bean
    public SemanticTextSplitter semanticTextSplitter(
            EmbeddingModel embeddingModel,
            @Value("${app.rag.splitter.semantic-breakpoint-mode:percentile}") String breakpointMode,
            @Value("${app.rag.splitter.semantic-percentile:95}") double percentile,
            @Value("${app.rag.splitter.semantic-threshold:0.7}") double threshold,
            @Value("${app.rag.splitter.semantic-buffer-size:3}") int bufferSize,
            @Value("${app.rag.splitter.semantic-max-chunk:800}") int maxChunk) {
        return new SemanticTextSplitter(
                embeddingModel,
                "fixed".equalsIgnoreCase(breakpointMode)
                        ? SemanticTextSplitter.BreakpointMode.FIXED
                        : SemanticTextSplitter.BreakpointMode.PERCENTILE,
                percentile, threshold, bufferSize, maxChunk);
    }

    @Bean
    public DocumentSplitterFactory documentSplitterFactory(
            @Value("${app.rag.splitter.strategy:semantic}") String strategy,
            @Value("${app.rag.splitter.fixed-chunk-size:500}") int fixedChunkSize,
            SemanticTextSplitter semanticTextSplitter) {
        return new DocumentSplitterFactory(strategy, fixedChunkSize, semanticTextSplitter);
    }

    @Bean
    public LongTermMemoryService longTermMemoryService(VectorMemoryStore vectorMemoryStore, MessageCodec messageCodec) {
        return new LongTermMemoryService(vectorMemoryStore, messageCodec);
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory) {
        return chatClientBuilder
                .defaultSystem(SystemPrompt.GENERAL)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new MessageChatMemoryAdvisor(chatMemory, "default", 20)
                )
                .build();
    }
}
