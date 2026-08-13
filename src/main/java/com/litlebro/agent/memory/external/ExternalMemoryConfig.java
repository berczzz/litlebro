package com.litlebro.agent.memory.external;

import com.litlebro.agent.memory.MemoryModeConditions;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 外部存储配置：STM 用 Redis，LTM 用 Milvus 向量库。
 */
@Configuration
@Conditional(MemoryModeConditions.ExternalMemoryCondition.class)
public class ExternalMemoryConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new JdkSerializationRedisSerializer());
        template.setHashValueSerializer(new JdkSerializationRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public ChatMemory chatMemory(RedisTemplate<String, Object> redisTemplate) {
        return new RedisChatMemory(redisTemplate);
    }

    @Bean
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
    public VectorStore vectorStore(MilvusServiceClient milvusClient,
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
}