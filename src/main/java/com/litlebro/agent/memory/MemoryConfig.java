package com.litlebro.agent.memory;

import com.litlebro.agent.common.SystemPrompt;
import com.litlebro.agent.context.CompressionService;
import com.litlebro.agent.context.ContextManager;
import com.litlebro.agent.session.SessionManager;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 记忆模块的装配中心，负责创建和装配 STM + LTM 二层记忆相关的 Bean。
 *
 * <p>存储模式切换：通过 {@code app.memory.mode} 控制，
 * 可选值 {@code auto}（默认）/ {@code memory} / {@code external}。
 * 无 Redis/Milvus 时自动回退内存实现。
 */
@Configuration
@EnableAsync
public class MemoryConfig {

    @Bean
    public SessionManager sessionManager(
            @Autowired(required = false) RedisTemplate<String, Object> redisTemplate) {
        return new SessionManager(redisTemplate);
    }

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
    public ContextManager contextManager(ChatMemory chatMemory,
                                          CompressionService compressionService,
                                          LongTermMemoryService longTermMemoryService,
                                          SessionManager sessionManager) {
        return new ContextManager(chatMemory, compressionService, longTermMemoryService, sessionManager);
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