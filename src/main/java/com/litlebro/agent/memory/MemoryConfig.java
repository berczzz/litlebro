package com.litlebro.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.litlebro.agent.common.SystemPrompt;
import com.litlebro.agent.context.CompressionService;
import com.litlebro.agent.memory.external.RedisChatMemory;
import com.litlebro.agent.session.SessionManager;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 记忆模块装配中心。
 *
 * <p>短期记忆（STM）与长期记忆（LTM）各自独立配置，互不影响：
 * <ul>
 *   <li>{@code app.memory.stm.type} — 短期记忆存储，可选 {@code inmemory}（默认）/ {@code redis}</li>
 *   <li>{@code app.memory.ltm.type} — 长期记忆存储，可选 {@code inmemory}（默认）/ {@code milvus}</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class MemoryConfig {

    // ==================== 短期记忆（STM）====================

    @Bean
    @ConditionalOnProperty(name = "app.memory.stm.type", havingValue = "inmemory", matchIfMissing = true)
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
    public ChatMemory redisChatMemory(RedisTemplate<String, Object> stmRedisTemplate, ObjectMapper objectMapper) {
        return new RedisChatMemory(stmRedisTemplate, objectMapper);
    }

    // ==================== 长期记忆（LTM）====================

    @Bean
    @ConditionalOnProperty(name = "app.memory.ltm.type", havingValue = "inmemory", matchIfMissing = true)
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

    /**
     * 会话状态存储跟随短期记忆：STM 用 Redis 时也存 Redis（30min TTL），否则回退内存。
     */
    @Bean
    public SessionManager sessionManager(
            @Autowired(required = false) @Qualifier("stmRedisTemplate") RedisTemplate<String, Object> stmRedisTemplate,
            ObjectMapper objectMapper) {
        return new SessionManager(stmRedisTemplate, objectMapper);
    }

    // ==================== 业务装配 ====================

    @Bean
    public VectorMemoryStore vectorMemoryStore(VectorStore vectorStore) {
        return new VectorMemoryStore(vectorStore);
    }

    @Bean
    public CompressionService compressionService(ChatClient chatClient) {
        return new CompressionService(chatClient);
    }

    @Bean
    public LongTermMemoryService longTermMemoryService(VectorMemoryStore vectorMemoryStore) {
        return new LongTermMemoryService(vectorMemoryStore);
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