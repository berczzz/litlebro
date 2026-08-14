package com.litlebro.agent.controller;

import com.litlebro.agent.attachment.resolver.AttachmentInput;
import com.litlebro.agent.dto.ChatRequest;
import com.litlebro.agent.dto.ChatResponse;
import com.litlebro.agent.dto.FileAttachment;
import com.litlebro.agent.service.AgentService;
import com.litlebro.agent.service.AgentStreamService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent REST API 控制器。
 *
 * <p>API 端点：
 * <ul>
 *   <li>POST /api/agent/chat — 核心对话接口（JSON，附件 base64/url 来源）</li>
 *   <li>POST /api/agent/chat/multipart — 核心对话接口（multipart，附件文件上传）</li>
 *   <li>POST /api/agent/chat/stream — 流式对话接口（SSE，输出思考/工具调用/回答增量）</li>
 *   <li>GET /api/agent/tools — 查询可用工具列表</li>
 *   <li>GET /api/agent/session/{sessionId} — 查询会话轮次计数</li>
 *   <li>GET /api/agent/memory/{sessionId} — 查询会话长期记忆</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final AgentStreamService agentStreamService;

    public AgentController(AgentService agentService, AgentStreamService agentStreamService) {
        this.agentService = agentService;
        this.agentStreamService = agentStreamService;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        List<AttachmentInput> attachments = toAttachmentInputs(request.attachments());
        String answer = agentService.chat(request.question(), request.sessionId(), attachments);
        return ChatResponse.of(request.question(), answer, request.sessionId(), null);
    }

    @PostMapping(value = "/chat/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chatMultipart(
            @RequestPart("question") String question,
            @RequestPart(value = "sessionId", required = false) String sessionId,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        List<AttachmentInput> attachments = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                attachments.add(new AttachmentInput("multipart", null, null, null, file));
            }
        }
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        String answer = agentService.chat(question, sid, attachments);
        return ChatResponse.of(question, answer, sid, null);
    }

    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        List<AttachmentInput> attachments = toAttachmentInputs(request.attachments());
        // 0L 表示不超时：模型思考/长回答耗时可能远超默认 30 秒
        SseEmitter emitter = new SseEmitter(0L);
        agentStreamService.streamChat(request.question(), request.sessionId(), attachments, emitter);
        return emitter;
    }

    @PostMapping(value = "/chat/stream/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStreamMultipart(
            @RequestPart("question") String question,
            @RequestPart(value = "sessionId", required = false) String sessionId,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        List<AttachmentInput> attachments = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                attachments.add(new AttachmentInput("multipart", null, null, null, file));
            }
        }
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        SseEmitter emitter = new SseEmitter(0L);
        agentStreamService.streamChat(question, sid, attachments, emitter);
        return emitter;
    }

    private List<AttachmentInput> toAttachmentInputs(List<FileAttachment> fileAttachments) {
        List<AttachmentInput> result = new ArrayList<>();
        if (fileAttachments == null) {
            return result;
        }
        for (FileAttachment fa : fileAttachments) {
            result.add(new AttachmentInput(fa.dataType(), fa.name(), fa.mimeType(), fa.data(), null));
        }
        return result;
    }

    @GetMapping("/tools")
    public Map<String, Object> tools() {
        return Map.of(
                "tools", agentService.getToolList(),
                "description", "本Agent支持以下工具，LLM会根据用户问题自动选择合适的工具"
        );
    }

    @GetMapping("/session/{sessionId}")
    public Map<String, Object> sessionInfo(@PathVariable("sessionId") String sessionId) {
        return agentService.getSessionInfo(sessionId);
    }

    @GetMapping("/memory/{sessionId}")
    public Map<String, Object> sessionMemory(@PathVariable("sessionId") String sessionId) {
        return agentService.getSessionMemory(sessionId);
    }
}