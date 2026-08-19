package com.litlebro.agent.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * RestClient 的 JSON 反序列化定制，统一放宽对未知字段的容忍度。
 *
 * <p>背景：Spring AI 的 OpenAiApi 通过
 * {@code restClientBuilderProvider.getIfAvailable(RestClient::builder)} 获取
 * RestClient.Builder。若直接使用 {@code RestClient::builder} 回退（裸构造），
 * 其默认 MessageConverters 使用"严格模式"的 Jackson（遇到未知字段即抛异常）。
 * 而阿里 MaaS/DashScope 网关的 Qwen 模型会在响应 message 中额外返回
 * {@code reasoning_content} 等 Spring AI M6 DTO 尚未收录的字段，
 * 导致 {@code Unrecognized field "reasoning_content"} 反序列化失败，整个对话报错。
 *
 * <p>解决：在本类中直接注册一个自带的 {@link RestClient.Builder} Bean，
 * 显式注入"忽略未知字段"的 Jackson MessageConverter。由于它是容器中唯一的
 * RestClient.Builder，OpenAiApi 自动装配时必定拿到本 Bean，从而获得
 * 前向兼容能力（对上游模型新增字段安全忽略）。
 *
 * <p>同时配置 HTTP 连接/读超时：JDK HttpClient 默认无请求超时，
 * 上游 LLM 端点挂起会让阻塞链路（对话/路由/视觉/压缩/embedding）的请求线程无限阻塞。
 */
@Configuration
public class RestClientConfig {

    /**
     * 提供宽松 JSON 反序列化 + HTTP 超时的 RestClient.Builder。
     *
     * <p>仅注册 String 与 Jackson 两种 MessageConverter：
     * <ul>
     *   <li>StringHttpMessageConverter — 通用文本响应</li>
     *   <li>MappingJackson2HttpMessageConverter — 配置为忽略未知字段，兼容 Qwen 扩展字段</li>
     * </ul>
     */
    @Bean
    public RestClient.Builder aiRestClientBuilder() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MappingJackson2HttpMessageConverter jackson = new MappingJackson2HttpMessageConverter(mapper);
        // 连接超时 10 秒 + 读超时 60 秒，防止上游挂起拖死请求线程
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(60));
        return RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters(List.of(
                        new StringHttpMessageConverter(),
                        jackson
                ));
    }
}
