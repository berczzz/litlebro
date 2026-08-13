package com.litlebro.agent.memory.inmemory;

import com.litlebro.agent.memory.MemoryModeConditions;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 内存存储配置：STM + LTM 全部使用进程内存实现。
 * 无 Redis/Milvus 时默认生效，应用重启后数据丢失。
 */
@Configuration
@Conditional(MemoryModeConditions.InMemoryMemoryCondition.class)
public class InMemoryMemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        return new InMemoryChatMemory();
    }

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}