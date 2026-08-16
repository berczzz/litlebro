package com.litlebro.agent.controller;

import com.litlebro.agent.attachment.resolver.AttachmentInput;
import com.litlebro.agent.dto.ChatRequest;
import com.litlebro.agent.dto.ChatResponse;
import com.litlebro.agent.dto.FileAttachment;
import com.litlebro.agent.service.AgentService;
import com.litlebro.agent.service.AgentStreamService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 对话 REST API 控制器。
 *
 * <p>API 端点：
 * <ul>
 *   <li>POST /api/agent/chat — 核心对话接口（JSON，附件 base64/url 来源）</li>
 *   <li>POST /api/agent/chat/multipart — 核心对话接口（multipart，附件文件上传）</li>
 *   <li>POST /api/agent/chat/stream — 流式对话接口（SSE，输出思考/工具调用/回答增量）</li>
 *   <li>POST /api/agent/chat/stream/multipart — 流式对话接口（multipart，同上）</li>
 * </ul>
 *
 * <p>工具管理见 {@link ToolController}（/api/agent/tools），会话状态见 {@link SessionController}，
 * 会话记忆见 {@link MemoryController}。
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
        String answer = agentService.chat(request.question(), request.sessionId(), attachments,
                request.skillIds(), request.mcpServerIds());
        return ChatResponse.of(request.question(), answer, request.sessionId(), null);
    }

    @PostMapping(value = "/chat/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chatMultipart(
            @RequestPart("question") String question,
            @RequestPart(value = "sessionId", required = false) String sessionId,
            @RequestPart(value = "skillIds", required = false) String skillIds,
            @RequestPart(value = "mcpServerIds", required = false) String mcpServerIds,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        List<AttachmentInput> attachments = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                attachments.add(new AttachmentInput("multipart", null, null, null, file));
            }
        }
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        String answer = agentService.chat(question, sid, attachments,
                parseIds(skillIds), parseIds(mcpServerIds));
        return ChatResponse.of(question, answer, sid, null);
    }

    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        List<AttachmentInput> attachments = toAttachmentInputs(request.attachments());
        // 同步校验技能/MCP 名单（非法 ID 转 400）；流式处理在异步线程，无法再映射 HTTP 状态码
        agentStreamService.validate(request.sessionId(), request.skillIds(), request.mcpServerIds());
        // 0L 表示不超时：模型思考/长回答耗时可能远超默认 30 秒
        SseEmitter emitter = new SseEmitter(0L);
        agentStreamService.streamChat(request.question(), request.sessionId(), attachments,
                request.skillIds(), request.mcpServerIds(), emitter);
        return emitter;
    }

    @PostMapping(value = "/chat/stream/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStreamMultipart(
            @RequestPart("question") String question,
            @RequestPart(value = "sessionId", required = false) String sessionId,
            @RequestPart(value = "skillIds", required = false) String skillIds,
            @RequestPart(value = "mcpServerIds", required = false) String mcpServerIds,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        List<AttachmentInput> attachments = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                attachments.add(new AttachmentInput("multipart", null, null, null, file));
            }
        }
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
        List<String> skillIdList = parseIds(skillIds);
        List<String> mcpServerIdList = parseIds(mcpServerIds);
        // 同步校验技能/MCP 名单（非法 ID 转 400）
        agentStreamService.validate(sid, skillIdList, mcpServerIdList);
        SseEmitter emitter = new SseEmitter(0L);
        agentStreamService.streamChat(question, sid, attachments, skillIdList, mcpServerIdList, emitter);
        return emitter;
    }

    /**
     * 解析 multipart 传入的 ID 列表（逗号分隔字符串，可空）。
     */
    private List<String> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
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
}