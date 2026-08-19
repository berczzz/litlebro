package com.litlebro.agent.tool.attachment;

import com.litlebro.agent.attachment.AttachmentStore;
import com.litlebro.agent.context.SessionContextHolder;
import com.litlebro.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 附件文件读取工具，供 LLM 按行读取用户上传附件（txt/pdf/word/excel 等）的内容。
 *
 * <p>配合附件直传机制：文档/文本附件以 fileId 落盘登记后，LLM 通过本工具
 * 按需读取指定行范围，避免将整个文件塞入上下文。
 *
 * <p>安全约定：只接收 fileId（经注册表校验归属当前会话），不暴露物理路径。
 */
@Component
public class ReadFileTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);

    /** 单次读取的最大行数窗口（与 AttachmentStore 的 toolMaxLines 对齐） */
    private final int toolMaxLines;

    private final AttachmentStore attachmentStore;

    public ReadFileTool(AttachmentStore attachmentStore,
                        @Value("${app.attachment.tool-max-lines:500}") int toolMaxLines) {
        this.attachmentStore = attachmentStore;
        this.toolMaxLines = Math.max(1, toolMaxLines);
    }

    @Override
    public String name() {
        return "附件文件读取";
    }

    @Override
    public String description() {
        return "读取用户随消息上传的附件文件（txt/pdf/word/excel 等）指定行范围的内容；仅作用于本次上传的附件，与全局文档知识库无关";
    }

    /**
     * 读取附件文件指定行范围的内容。
     *
     * @param fileId    附件唯一标识（fileId）
     * @param startLine 起始行号（从 1 开始）
     * @param endLine   结束行号（含），超出文件末尾时自动收拢
     * @return 带行号标注的文本内容
     */
    @Tool(name = "read_file", description = "读取随消息上传的附件文件内容。当用户上传了文档/文本类附件并需要读取其中内容时调用，可指定行范围切片读取；单次最多读取 500 行，超出自动截断并提示续读位置，建议小范围多次读取。注意：本工具只读取本次随消息上传的附件，不检索全局文档知识库（那是 search_document 的职责）")
    public String readFile(
            @ToolParam(description = "附件唯一标识 fileId，随消息一起提供") String fileId,
            @ToolParam(description = "起始行号（从 1 开始）") int startLine,
            @ToolParam(description = "结束行号（含），超出文件末尾时自动收拢，-1 表示读取到末尾；单次读取行数受限，超出自动截断并提示续读位置") int endLine) {
        String sessionId = SessionContextHolder.get();
        log.info("read_file执行开始 fileId={} start={} end={} sessionId={}", fileId, startLine, endLine, sessionId);
        if (!attachmentStore.belongsTo(fileId, sessionId)) {
            return "附件不存在或不属于当前会话，请确认 fileId 是否正确。";
        }
        try {
            // 钳制结束行号：正数时限制在 toolMaxLines 行窗口内，防止模型一次性读取整个大文件
            int end = endLine < 0 ? -1 : (int) Math.min((long) endLine, (long) startLine + toolMaxLines - 1);
            String content = attachmentStore.readLines(fileId, startLine, end);
            if (content == null) {
                return "附件内容读取失败，可能已被清理。";
            }
            log.info("read_file执行完成 fileId={} 返回长度={}", fileId, content.length());
            return content;
        } catch (IOException e) {
            log.warn("read_file执行失败 fileId={} 原因: {}", fileId, e.getMessage());
            return "附件读取失败: " + e.getMessage();
        }
    }
}