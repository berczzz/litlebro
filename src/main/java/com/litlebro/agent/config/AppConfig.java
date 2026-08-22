package com.litlebro.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.attachment.AttachmentRegistry;
import com.litlebro.agent.attachment.external.RedisAttachmentRegistry;
import com.litlebro.agent.attachment.local.LocalAttachmentRegistry;
import com.litlebro.agent.common.SystemPrompt;
import com.litlebro.agent.context.CompressionService;
import com.litlebro.agent.embedding.CachedEmbeddingModel;
import com.litlebro.agent.embedding.EmbeddingCache;
import com.litlebro.agent.embedding.external.RedisEmbeddingCache;
import com.litlebro.agent.embedding.local.LocalEmbeddingCache;
import com.litlebro.agent.memory.LongTermMemoryService;
import org.springframework.ai.document.MetadataMode;
import com.litlebro.agent.memory.MessageCodec;
import com.litlebro.agent.memory.ShardedMilvusVectorStoreRouter;
import com.litlebro.agent.memory.SingleVectorStoreRouter;
import com.litlebro.agent.memory.VectorMemoryStore;
import com.litlebro.agent.memory.VectorStoreRouter;
import com.litlebro.agent.memory.external.RedisChatMemory;
import com.litlebro.agent.mcp.McpServerProperties;
import com.litlebro.agent.mcp.external.RedisMcpServerStore;
import com.litlebro.agent.mcp.local.LocalMcpServerStore;
import com.litlebro.agent.mcp.store.McpServerStore;
import com.litlebro.agent.rag.DocumentParseCache;
import com.litlebro.agent.rag.DocumentSplitterFactory;
import com.litlebro.agent.rag.SemanticTextSplitter;
import com.litlebro.agent.rag.external.RedisDocumentParseCache;
import com.litlebro.agent.rag.local.LocalDocumentParseCache;
import com.litlebro.agent.router.RouterDecision;
import com.litlebro.agent.router.RouterProperties;
import com.litlebro.agent.session.SessionManager;
import com.litlebro.agent.session.external.RedisSessionManager;
import com.litlebro.agent.session.local.LocalSessionManager;
import com.litlebro.agent.skill.LocalSkillExecutor;
import com.litlebro.agent.skill.SkillExecutor;
import com.litlebro.agent.skill.SkillProperties;
import com.litlebro.agent.skill.external.RedisSkillStore;
import com.litlebro.agent.skill.local.LocalSkillStore;
import com.litlebro.agent.skill.store.SkillStore;
import com.litlebro.agent.tool.ToolDisabledStore;
import com.litlebro.agent.tool.external.RedisToolDisabledStore;
import com.litlebro.agent.tool.local.LocalToolDisabledStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import com.litlebro.agent.vectorstore.BatchingSimpleVectorStore;
import com.litlebro.agent.vectorstore.CountBatchingStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

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
@EnableConfigurationProperties({SkillProperties.class, McpServerProperties.class, RouterProperties.class})
public class AppConfig {

    // ==================== Redis 公共基础设施 ====================

    /**
     * 全局共享的 RedisTemplate（String 序列化，值存 JSON 字符串）。
     * STM / 会话状态 / 文档解析缓存 / 附件注册表 各功能独立切换 local/redis，
     */
    @Bean
    public RedisTemplate<String, Object> appRedisTemplate(RedisConnectionFactory connectionFactory) {
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

    // ==================== OpenAI 模型（手动装配，替代 auto-configuration）====================

    /**
     * OpenAI API 客户端：复用主对话端点与 Key，供 chat / embedding 共用。
     * 排除 {@code OpenAiAutoConfiguration} 后需手动创建。
     */
    @Bean
    public OpenAiApi openAiApi(LlmSettings llm, RestClient.Builder restClientBuilder) {
        OpenAiApi.Builder builder = OpenAiApi.builder()
                .baseUrl(llm.getChatBaseUrl())
                .restClientBuilder(restClientBuilder);
        if (StringUtils.hasText(llm.getChatApiKey())) {
            builder.apiKey(llm.getChatApiKey());
        }
        return builder.build();
    }

    /**
     * 主对话模型：替代 auto-configuration 的 openAiChatModel。
     */
    @Bean
    public OpenAiChatModel openAiChatModel(OpenAiApi openAiApi, LlmSettings llm) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(llm.getChatModel())
                .temperature(llm.getChatTemperature())
                .maxTokens(llm.getChatMaxTokens())
                .build();
        return new OpenAiChatModel(openAiApi, options);
    }

    // ==================== Embedding 缓存 ====================

    /**
     * Embedding 缓存本地内存实现：有上限 LRU，进程内共享，重启丢失。
     * 由配置 {@code app.embedding.cache.type=local}（默认）装配。
     */
    @Bean
    @ConditionalOnProperty(name = "app.embedding.cache.type", havingValue = "local", matchIfMissing = true)
    public EmbeddingCache localEmbeddingCache(
            @Value("${app.embedding.cache.max-entries:5000}") int maxEntries) {
        return new LocalEmbeddingCache(maxEntries);
    }

    /**
     * Embedding 缓存 Redis 实现：以文本哈希为 key 存入 Redis（TTL），重启不丢。
     * 由配置 {@code app.embedding.cache.type=redis} 装配。
     */
    @Bean
    @ConditionalOnProperty(name = "app.embedding.cache.type", havingValue = "redis")
    public EmbeddingCache redisEmbeddingCache(
            @Qualifier("appRedisTemplate") RedisTemplate<String, Object> appRedisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.embedding.cache.ttl-hours:24}") long ttlHours) {
        return new RedisEmbeddingCache(appRedisTemplate, objectMapper, ttlHours);
    }

    /**
     * Embedding 模型（带缓存装饰器）：包装原始 OpenAiEmbeddingModel，
     * 对每次 embedding 调用先查缓存、仅对未命中的文本调用底层模型。
     * 缓存命中时跳过 HTTP 调用，节省 embedding token 与网络开销。
     */
    @Bean
    public CachedEmbeddingModel cachedEmbeddingModel(OpenAiApi openAiApi, LlmSettings llm,
                                                     EmbeddingCache cache) {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(llm.getEmbedModel())
                .build();
        OpenAiEmbeddingModel delegate = new OpenAiEmbeddingModel(openAiApi, MetadataMode.NONE, options);
        return new CachedEmbeddingModel(delegate, cache);
    }

    /**
     * 将 CachedEmbeddingModel 注册为 Spring AI 标准 EmbeddingModel bean，
     * 供 VectorStore / SemanticTextSplitter 等自动注入使用。
     */
    @Bean
    public EmbeddingModel embeddingModel(CachedEmbeddingModel cachedEmbeddingModel) {
        return cachedEmbeddingModel;
    }

    // ==================== 短期记忆（STM）====================

    @Bean
    @ConditionalOnProperty(name = "app.memory.stm.type", havingValue = "local", matchIfMissing = true)
    public ChatMemory inMemoryChatMemory() {
        return new InMemoryChatMemory();
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.stm.type", havingValue = "redis")
    public ChatMemory redisChatMemory(@Qualifier("appRedisTemplate") RedisTemplate<String, Object> appRedisTemplate,
                                      ObjectMapper objectMapper, MessageCodec messageCodec) {
        return new RedisChatMemory(appRedisTemplate, objectMapper, messageCodec);
    }

    // ==================== 长期记忆（LTM）====================

    @Bean
    @ConditionalOnProperty(name = "app.memory.ltm.type", havingValue = "local", matchIfMissing = true)
    public VectorStore simpleVectorStore(EmbeddingModel embeddingModel,
                                         @Value("${app.memory.vector.embed-batch-size:10}") int embedBatchSize) {
        return new BatchingSimpleVectorStore(SimpleVectorStore.builder(embeddingModel), embedBatchSize);
    }

    @Bean
    @ConditionalOnProperty(name = "app.memory.ltm.type", havingValue = "local", matchIfMissing = true)
    public VectorStoreRouter singleVectorStoreRouter(VectorStore simpleVectorStore) {
        return new SingleVectorStoreRouter(simpleVectorStore, "agent_memories");
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

    /**
     * Milvus 分片向量库路由器：按 {@code sessionId} 哈希取模路由到 N 个分片 collection，
     * 文档知识库走独立 collection（{@code agent_memories_doc}）。
     *
     * <p>分片数由 {@code app.memory.ltm.milvus-shards} 控制（默认 16）。分片天然摊薄单 collection
     * 数据量与 filter 扫描范围；metadata 为 JSON 字段无法建标量索引（Milvus 2.3），
     * 但分片 + sessionId 过滤已足够收敛查询集。
     *
     * <p>collection 首次写入时由 {@code initializeSchema(true)} 自动建表，无需手工迁移；
     * 全新部署无存量数据，无需数据搬迁。
     */
    @Bean
    @ConditionalOnProperty(name = "app.memory.ltm.type", havingValue = "milvus")
    public VectorStoreRouter milvusShardedRouter(MilvusServiceClient milvusClient,
                                                 EmbeddingModel embeddingModel,
                                                 @Value("${app.memory.vector.embed-batch-size:10}") int embedBatchSize,
                                                 @Value("${spring.ai.vectorstore.milvus.database-name:default}") String databaseName,
                                                 @Value("${app.memory.ltm.milvus-collection-prefix:agent_memories}") String collectionPrefix,
                                                 @Value("${app.memory.ltm.milvus-shards:16}") int shards,
                                                 @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1536}") int embeddingDimension,
                                                 @Value("${spring.ai.vectorstore.milvus.index-type:IVF_FLAT}") String indexType,
                                                 @Value("${spring.ai.vectorstore.milvus.metric-type:COSINE}") String metricType) {
        int shardCount = Math.max(1, shards);
        List<VectorStore> memoryShards = new ArrayList<>(shardCount);
        List<String> names = new ArrayList<>(shardCount + 1);
        for (int i = 0; i < shardCount; i++) {
            String name = collectionPrefix + "_" + i;
            memoryShards.add(buildMilvusStore(milvusClient, embeddingModel, databaseName, name,
                    embedBatchSize, embeddingDimension, indexType, metricType));
            names.add(name);
        }
        String docName = collectionPrefix + "_doc";
        VectorStore docStore = buildMilvusStore(milvusClient, embeddingModel, databaseName, docName,
                embedBatchSize, embeddingDimension, indexType, metricType);
        names.add(docName);
        return new ShardedMilvusVectorStoreRouter(memoryShards, docStore, names);
    }

    private static VectorStore buildMilvusStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel,
                                                String databaseName, String collectionName, int embedBatchSize,
                                                int embeddingDimension, String indexType, String metricType) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .databaseName(databaseName)
                .collectionName(collectionName)
                .embeddingDimension(embeddingDimension)
                .indexType(IndexType.valueOf(indexType))
                .metricType(MetricType.valueOf(metricType))
                .initializeSchema(true)
                .batchingStrategy(new CountBatchingStrategy(embedBatchSize))
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
            @Qualifier("appRedisTemplate") RedisTemplate<String, Object> appRedisTemplate,
            ObjectMapper objectMapper) {
        return new RedisSessionManager(appRedisTemplate, objectMapper);
    }

    // ==================== 文档解析缓存 ====================

    @Bean
    @ConditionalOnProperty(name = "app.rag.cache.type", havingValue = "local", matchIfMissing = true)
    public DocumentParseCache localDocumentParseCache(
            @Value("${app.rag.cache.max-entries:100}") int maxEntries) {
        return new LocalDocumentParseCache(maxEntries);
    }

    @Bean
    @ConditionalOnProperty(name = "app.rag.cache.type", havingValue = "redis")
    public DocumentParseCache redisDocumentParseCache(
            @Qualifier("appRedisTemplate") RedisTemplate<String, Object> appRedisTemplate,
            @Value("${app.rag.cache.parse-ttl-hours:24}") long ttlHours) {
        return new RedisDocumentParseCache(appRedisTemplate, ttlHours);
    }

    // ==================== 附件注册表 ====================

    /**
     * 附件注册表存储由 {@code app.attachment.registry.type} 显式决定：
     * {@code redis} 时写入 Redis（TTL 与附件过期时间对齐），重启不丢；
     * {@code local}（默认）时写入本地内存，重启丢失。与短期记忆配置互不影响。
     */
    @Bean
    @ConditionalOnProperty(name = "app.attachment.registry.type", havingValue = "local", matchIfMissing = true)
    public AttachmentRegistry localAttachmentRegistry() {
        return new LocalAttachmentRegistry();
    }

    @Bean
    @ConditionalOnProperty(name = "app.attachment.registry.type", havingValue = "redis")
    public AttachmentRegistry redisAttachmentRegistry(
            @Qualifier("appRedisTemplate") RedisTemplate<String, Object> appRedisTemplate,
            ObjectMapper objectMapper) {
        return new RedisAttachmentRegistry(appRedisTemplate, objectMapper);
    }

    // ==================== 工具禁用状态 ====================

    /**
     * 工具禁用状态存储由 {@code app.tool.store.type} 显式决定：
     * {@code redis} 时写入 Redis（Hash {@code agent:tool:disabled}，无 TTL），重启不丢；
     * {@code local}（默认）时写入本地内存，重启丢失。
     */
    @Bean
    @ConditionalOnProperty(name = "app.tool.store.type", havingValue = "local", matchIfMissing = true)
    public ToolDisabledStore localToolDisabledStore() {
        return new LocalToolDisabledStore();
    }

    @Bean
    @ConditionalOnProperty(name = "app.tool.store.type", havingValue = "redis")
    public ToolDisabledStore redisToolDisabledStore(
            @Qualifier("appRedisTemplate") RedisTemplate<String, Object> appRedisTemplate) {
        return new RedisToolDisabledStore(appRedisTemplate);
    }

    // ==================== 技能（Skills）====================

    /**
     * 技能存储由 {@code app.skill.store.type} 显式决定：
     * {@code redis} 时写入 Redis（技能定义 + 会话技能记录无 TTL），重启不丢；
     * {@code local}（默认）时写入本地内存，重启丢失。由 {@code app.skill.enabled=true} 时生效。
     */
    @Bean
    @ConditionalOnExpression("${app.skill.enabled:false} && '${app.skill.store.type:local}'.equals('local')")
    public SkillStore localSkillStore() {
        return new LocalSkillStore();
    }

    /**
     * 技能专用 RedisTemplate（与全局 appRedisTemplate 隔离，避免与 STM/会话等共用导致相互干扰）。
     */
    @Bean("skillRedisTemplate")
    @ConditionalOnExpression("${app.skill.enabled:false} && '${app.skill.store.type:redis}'.equals('redis')")
    public RedisTemplate<String, Object> skillRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnExpression("${app.skill.enabled:false} && '${app.skill.store.type:redis}'.equals('redis')")
    public SkillStore redisSkillStore(
            @Qualifier("skillRedisTemplate") RedisTemplate<String, Object> skillRedisTemplate,
            ObjectMapper objectMapper) {
        return new RedisSkillStore(skillRedisTemplate, objectMapper);
    }

    /**
     * v1 仅提供本地进程执行器；后续 Docker 沙箱 / 远程执行只需新增 SkillExecutor 实现并按配置切换。
     */
    @Bean
    @ConditionalOnProperty(name = "app.skill.enabled", havingValue = "true")
    public SkillExecutor skillExecutor() {
        return new LocalSkillExecutor();
    }

    // ==================== MCP（Model Context Protocol）====================

    /**
     * MCP 服务器存储由 {@code app.mcp.store.type} 显式决定（{@code app.mcp.enabled=true} 时生效）：
     * {@code redis} 时写入 Redis（Hash {@code agent:mcp:servers} + {@code agent:mcp:recorded:{sessionId}}，无 TTL），
     * 重启不丢；{@code local}（默认）时写入本地内存，重启丢失。
     */
    @Bean
    @ConditionalOnExpression("${app.mcp.enabled:false} && '${app.mcp.store.type:local}'.equals('local')")
    public McpServerStore localMcpServerStore() {
        return new LocalMcpServerStore();
    }

    @Bean
    @ConditionalOnExpression("${app.mcp.enabled:false} && '${app.mcp.store.type:redis}'.equals('redis')")
    public McpServerStore redisMcpServerStore(
            @Qualifier("appRedisTemplate") RedisTemplate<String, Object> appRedisTemplate,
            ObjectMapper objectMapper) {
        return new RedisMcpServerStore(appRedisTemplate, objectMapper);
    }

    // ==================== 业务装配 ====================

    /**
     * 检索路由专用 ChatClient：仅在路由层启用且歧义词启用 LLM 兜底时装配。
     *
     * <p>独立构建 OpenAI 兼容端点（可与主对话端点不同，如 qwen/DeepSeek），
     * 三项配置空值回落主对话配置（base-url→{@code spring.ai.openai.base-url}，
     * api-key→{@code spring.ai.openai.api-key}，model→{@code spring.ai.openai.chat.options.model}），
     * 便于只声明 base-url 而复用主 Key/模型。
     *
     * <p>路由任务只需输出一个短 JSON，故固定低温。结构化输出（response-format）非 none 时
     * 由服务端约束采样保证合法 JSON，此时不设 max-tokens（防止截断把 JSON 切半）。
     */
    @Bean("routerChatClient")
    @ConditionalOnExpression("${app.router.enabled:true} && ${app.router.llm-fallback:true}")
    public ChatClient routerChatClient(
            RouterProperties props, LlmSettings llm, RestClient.Builder restClientBuilder) {
        RouterProperties.Llm routerLlm = props.getLlm();
        String baseUrl = StringUtils.hasText(routerLlm.getBaseUrl()) ? routerLlm.getBaseUrl() : llm.getChatBaseUrl();
        String apiKey = StringUtils.hasText(routerLlm.getApiKey()) ? routerLlm.getApiKey() : llm.getChatApiKey();
        String model = StringUtils.hasText(routerLlm.getModel()) ? routerLlm.getModel() : llm.getChatModel();

        // 复用宽松 RestClient.Builder（忽略未知字段 + HTTP 超时），
        // 否则 OpenAiApi.builder() 默认裸 RestClient 为严格 Jackson 且无超时，
        // 遇到 qwen 的 reasoning_content 等扩展字段会直接反序列化失败
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().baseUrl(baseUrl).restClientBuilder(restClientBuilder);
        // 空 apiKey 不设置，避免 Builder 校验抛异常（如仅配 base-url 复用主 Key 场景）
        if (StringUtils.hasText(apiKey)) {
            apiBuilder.apiKey(apiKey);
        }

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(model)
                .temperature(routerLlm.getTemperature());
        String responseFormat = routerLlm.getResponseFormat();
        if ("none".equalsIgnoreCase(responseFormat)) {
            // 纯提示词模式：保留 max-tokens 控制输出成本
            optionsBuilder.maxTokens(routerLlm.getMaxTokens());
        } else {
            // 结构化输出：服务端约束采样保证 JSON 合法/符合 schema，不设 max-tokens 防截断
            ResponseFormat rf = "json_schema".equalsIgnoreCase(responseFormat)
                    ? ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                            .jsonSchema(ResponseFormat.JsonSchema.builder().name("router_decision")
                                    .schema(new BeanOutputConverter<>(RouterDecision.class).getJsonSchemaMap())
                                    .strict(true).build())
                            .build()
                    : ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();
            optionsBuilder.responseFormat(rf);
        }
        OpenAiChatOptions options = optionsBuilder.build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(options)
                .build();
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 视觉识别专用 ChatClient：独立构建 OpenAI 兼容端点。
     *
     * <p>可指向任意支持图片输入的 OpenAI 兼容多模态模型（dashscope qwen-vl /
     * OpenAI / 本地 vLLM / Ollama 等），默认回落主对话端点与 Key（{@code spring.ai.openai.*}）。
     *
     * <p>无 advisor/defaultSystem，与主对话 {@code chatClient} 隔离，避免视觉描述调用污染会话记忆。
     * 由 {@code app.rag.vision.enabled=true} 时装配，未启用时 {@link ObjectProvider#getIfAvailable()} 返回空。
     */
    @Bean("visionChatClient")
    @ConditionalOnProperty(name = "app.rag.vision.enabled", havingValue = "true")
    public ChatClient visionChatClient(LlmSettings llm, RestClient.Builder restClientBuilder) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(llm.resolveVisionBaseUrl())
                // 复用宽松 RestClient.Builder，保证未知字段容忍与 HTTP 超时（见 routerChatClient 注释）
                .restClientBuilder(restClientBuilder);
        // 空 apiKey 不设置，避免 Builder 校验抛异常
        if (StringUtils.hasText(llm.resolveVisionApiKey())) {
            apiBuilder.apiKey(llm.resolveVisionApiKey());
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(llm.resolveVisionModel())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(options)
                .build();
        return ChatClient.builder(chatModel).build();
    }

    // ==================== 异步任务 ====================

    /**
     * 上下文压缩专用 ChatClient：无 advisor/defaultSystem，与主对话 {@code chatClient} 隔离，
     * 避免压缩调用污染会话记忆（主 chatClient 挂 MessageChatMemoryAdvisor，会把它也当一轮对话写入）。
     *
     * <p>复用主对话 OpenAI 兼容端点与 Key/模型；固定低温 + json_object 结构化输出，
     * 配合 {@link CompressionService} 的 BeanOutputConverter 解析 {summary, facts[]}。
     */
    @Bean("compactionChatClient")
    public ChatClient compactionChatClient(LlmSettings llm, RestClient.Builder restClientBuilder) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(llm.getChatBaseUrl())
                // 复用宽松 RestClient.Builder，保证未知字段容忍与 HTTP 超时（见 routerChatClient 注释）
                .restClientBuilder(restClientBuilder);
        // 空 apiKey 不设置，避免 Builder 校验抛异常
        if (StringUtils.hasText(llm.getChatApiKey())) {
            apiBuilder.apiKey(llm.getChatApiKey());
        }
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(llm.getChatModel())
                .temperature(0.0)
                .maxTokens(8192)
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(options)
                .build();
        return ChatClient.builder(chatModel).build();
    }

    // ==================== 异步任务 ====================

    /**
     * 长期记忆持久化专用线程池：{@code @Async("ltmTaskExecutor")} 的 saveChats 在此执行，
     * 避免 embedding + 向量入库阻塞对话输出。
     */
    @Bean
    public ThreadPoolTaskExecutor ltmTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ltm-persist-");
        executor.initialize();
        return executor;
    }

    /**
     * 后台压缩专用线程池：{@code ContextManager.triggerCompactionIfNeeded} 在此执行，
     * 与 LTM 持久化（ltmTaskExecutor）隔离，避免压缩任务被 saveChats 排队拖住。
     */
    @Bean
    public ThreadPoolTaskExecutor compactionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("compact-");
        executor.initialize();
        return executor;
    }

    /**
     * 流式对话专用线程池：{@code @Async("streamExecutor")} 的 streamChat 在此执行。
     * 每个流式会话会占用一个线程直到 SSE 结束（模型-工具循环阻塞驱动），
     * 故核心线程数需兼顾并发会话数。
     */
    @Bean
    public ThreadPoolTaskExecutor streamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("stream-chat-");
        executor.initialize();
        return executor;
    }

    /**
     * 文档入库专用线程池：{@link VectorMemoryStore#saveDocumentChunks} 按切块分组并发写入向量库，
     * 每组内部仍按 {@code app.memory.vector.embed-batch-size}（默认 10 条/请求）分批 embedding。
     * 并发度即核心线程数，由 {@code app.rag.ingest-parallelism} 控制。
     */
    @Bean("ingestExecutor")
    public ThreadPoolTaskExecutor ingestExecutor(
            @Value("${app.rag.ingest-parallelism:4}") int parallelism) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(parallelism);
        executor.setMaxPoolSize(parallelism);
        executor.setQueueCapacity(5000);
        executor.setThreadNamePrefix("ingest-");
        executor.initialize();
        return executor;
    }

    @Bean
    public VectorMemoryStore vectorMemoryStore(
            VectorStoreRouter vectorStoreRouter,
            @Value("${app.memory.vector.similarity-threshold:0.2}") double similarityThreshold,
            @Qualifier("ingestExecutor") AsyncTaskExecutor ingestExecutor,
            @Value("${app.rag.ingest-parallelism:4}") int ingestParallelism) {
        return new VectorMemoryStore(vectorStoreRouter, similarityThreshold, ingestExecutor, ingestParallelism);
    }

    @Bean
    public CompressionService compressionService(@Qualifier("compactionChatClient") ChatClient compactionChatClient) {
        return new CompressionService(compactionChatClient);
    }

    @Bean
    public SemanticTextSplitter semanticTextSplitter(
            EmbeddingModel embeddingModel,
            @Value("${app.rag.splitter.semantic-breakpoint-mode:percentile}") String breakpointMode,
            @Value("${app.rag.splitter.semantic-percentile:95}") double percentile,
            @Value("${app.rag.splitter.semantic-threshold:0.7}") double threshold,
            @Value("${app.rag.splitter.semantic-buffer-size:3}") int bufferSize,
            @Value("${app.rag.splitter.semantic-max-chunk:800}") int maxChunk,
            @Value("${app.rag.splitter.semantic-max-segments:500}") int maxEmbedSegments,
            @Value("${app.memory.vector.embed-batch-size:10}") int embedBatchSize) {
        return new SemanticTextSplitter(
                embeddingModel,
                "fixed".equalsIgnoreCase(breakpointMode)
                        ? SemanticTextSplitter.BreakpointMode.FIXED
                        : SemanticTextSplitter.BreakpointMode.PERCENTILE,
                percentile, threshold, bufferSize, maxChunk, maxEmbedSegments, embedBatchSize);
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
