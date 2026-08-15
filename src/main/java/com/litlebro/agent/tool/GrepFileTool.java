package com.litlebro.agent.tool;

import com.litlebro.agent.attachment.AttachmentStore;
import com.litlebro.agent.context.SessionContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 附件文件内容检索工具，供 LLM 在用户上传的附件中按正则表达式定位内容。
 *
 * <p>与 {@code read_file} 互补：grep_file 适合在长文档中快速定位关键词，
 * 再用 read_file 精确读取上下文，避免全量读取大文件。
 */
@Component
public class GrepFileTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(GrepFileTool.class);

    /** 默认单次检索最多返回的匹配行数 */
    private final int defaultMaxLines;
    /** 单次检索最多返回匹配行数的硬上限（与 AttachmentStore 的 toolMaxLines 对齐） */
    private final int toolMaxLines;

    private final AttachmentStore attachmentStore;

    public GrepFileTool(AttachmentStore attachmentStore,
                        @Value("${app.attachment.max-lines:100}") int defaultMaxLines,
                        @Value("${app.attachment.tool-max-lines:500}") int toolMaxLines) {
        this.attachmentStore = attachmentStore;
        this.defaultMaxLines = defaultMaxLines;
        this.toolMaxLines = Math.max(1, toolMaxLines);
    }

    @Override
    public String name() {
        return "附件内容检索";
    }

    @Override
    public String description() {
        return "在用户上传的附件文件中按正则表达式检索内容，返回带行号的匹配行";
    }

    /**
     * 在附件文件中按正则检索内容。
     *
     * @param fileId   附件唯一标识（fileId）
     * @param pattern  正则表达式
     * @param maxLines 最多返回的匹配行数（超出截断），可不传使用默认值
     * @return 带行号的匹配内容
     */
    @Tool(name = "grep_file", description = "在随消息上传的附件文件中按正则表达式检索内容，返回带行号的匹配行。适合在长文档中快速定位关键词位置，单次最多返回 500 行，超出自动截断并提示，建议使用精确正则小范围检索")
    public String grepFile(
            @ToolParam(description = "附件唯一标识 fileId，随消息一起提供") String fileId,
            @ToolParam(description = "正则表达式，用于匹配附件内容中的目标文本") String pattern,
            @ToolParam(description = "最多返回的匹配行数（上限 500 行，超出截断；不传时使用默认值 100）") int maxLines) {
        String sessionId = SessionContextHolder.get();
        log.info("grep_file执行开始 fileId={} pattern={} sessionId={}", fileId, pattern, sessionId);
        if (!attachmentStore.belongsTo(fileId, sessionId)) {
            return "附件不存在或不属于当前会话，请确认 fileId 是否正确。";
        }
        try {
            // 钳制模型传入的行数：默认值兜底 + 硬上限约束，防止模型传超大值全量拉取
            int limit = Math.max(1, Math.min(maxLines > 0 ? maxLines : defaultMaxLines, toolMaxLines));
            String result = attachmentStore.grep(fileId, pattern, limit);
            if (result == null) {
                return "附件内容读取失败，可能已被清理。";
            }
            log.info("grep_file执行完成 fileId={} pattern={} 返回长度={}", fileId, pattern, result.length());
            return result;
        } catch (Exception e) {
            log.warn("grep_file执行失败 fileId={} pattern={} 原因: {}", fileId, pattern, e.getMessage());
            return "检索失败: " + e.getMessage();
        }
    }
}