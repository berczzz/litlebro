package com.litlebro.agent.common;

public final class Constant {

    private Constant() {
    }

    public static final String CATEGORY_SUMMARY = "session_summary";
    public static final String CATEGORY_CHAT = "chat_message";
    /** 持久事实：压缩时从对话中提炼的跨会话仍有价值的事实/决定/约定/偏好 */
    public static final String CATEGORY_FACT = "session_fact";
    public static final String CATEGORY_OTHER = "other";
    public static final String CATEGORY_DOCUMENT = "document";
    public static final String MEMORY_TYPE = "agent_memory";
    public static final String RAG_DOCUMENT_TYPE = "rag_document";

    // ==================== 记忆元数据字段名 ====================
    public static final String MD_ID = "id";
    public static final String MD_SESSION_ID = "sessionId";
    public static final String MD_CATEGORY = "category";
    public static final String MD_TYPE = "type";
    public static final String MD_MESSAGE_TYPE = "messageType";
    public static final String MD_CREATED_AT = "createdAt";
    public static final String MD_UPDATED_AT = "updatedAt";
    public static final String MD_MEDIA = "media";
    public static final String MD_TOOL_CALLS = "toolCalls";
    public static final String MD_TOOL_RESPONSES = "toolResponses";
    public static final String MD_SOURCE = "source";
    public static final String MD_DOC_ID = "docId";

    // ==================== 摘要 ====================
    /** 上下文压缩摘要文本存储于元数据的键 */
    public static final String SUMMARY_COST = "cost";
    /** 摘要元数据中的压缩点时间戳（epoch millis）：恢复上下文时只回注该时间点之后的消息 */
    public static final String MD_COMPACT_POINT = "compactPoint";

    // ==================== 消息序号（会话级单调递增，压缩边界定位用）====================
    /** 长期记忆中每条消息的会话级单调序号（写向量库元数据） */
    public static final String MD_SEQ = "seq";
    /** 会话状态 metadata 中记录"最近一次分配的消息序号"的键 */
    public static final String SESSION_META_LAST_SEQ = "lastMessageSeq";
    /** 会话状态 metadata 中轮次计数的键 */
    public static final String SESSION_META_TURN_COUNT = "turnCount";

    // ==================== 检索/读取条数限制 ====================
    /** 从长期记忆恢复短期记忆上下文时，最多回注的消息条数 */
    public static final int MAX_CONTEXT_RESTORE_MESSAGES = 500;
    /** 调试接口单次返回长期记忆的最大条数 */
    public static final int MAX_DEBUG_MEMORY_MESSAGES = 200;
    /** 调试接口单次返回事实的最大条数 */
    public static final int MAX_DEBUG_FACTS = 50;
}