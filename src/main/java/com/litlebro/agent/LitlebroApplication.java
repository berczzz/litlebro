package com.litlebro.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Agent 演示应用的主启动类。
 *
 * <p>整个系统的入口点，负责启动 Spring Boot 容器并自动装配所有组件。
 * 系统架构分为以下几个层次：
 * <ul>
 *   <li>controller — REST API 层，接收用户请求</li>
 *   <li>service — 业务逻辑层，协调 LLM 调用、工具执行和记忆管理</li>
 *   <li>tool — 工具层，提供 LLM 可调用的外部能力（日期、计算、天气）</li>
 *   <li>memory — 记忆层，管理短/中/长期记忆及上下文压缩</li>
 *   <li>dto — 数据传输对象，定义请求/响应结构</li>
 *   <li>exception — 全局异常处理</li>
 * </ul>
 */
@SpringBootApplication(exclude = {
        org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration.class
})
public class LitlebroApplication {

    /**
     * 应用主入口，启动内嵌 Web 服务器并初始化 Spring 容器。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(LitlebroApplication.class, args);
    }
}