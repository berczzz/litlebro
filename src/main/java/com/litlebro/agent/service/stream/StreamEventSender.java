package com.litlebro.agent.service.stream;

import com.litlebro.agent.dto.StreamEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * SSE 事件推送工具。客户端断开时抛异常终止后续流程。
 */
public final class StreamEventSender {

    private StreamEventSender() {
    }

    public static void send(SseEmitter emitter, String type, Map<String, Object> data) {
        try {
            emitter.send(new StreamEvent(type, data));
        } catch (IOException e) {
            throw new IllegalStateException("SSE 推送中断 (type=" + type + "): " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("SSE 连接已关闭 (type=" + type + "): " + e.getMessage(), e);
        }
    }
}