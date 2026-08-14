package com.litlebro.agent.common;

public final class Constant {

    private Constant() {
    }

    public static final String CATEGORY_SUMMARY = "session_summary";
    public static final String CATEGORY_CHAT = "chat_message";
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
    public static final String MD_ROLE = "role";
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

    // ==================== 检索/读取条数限制 ====================
    /** 从长期记忆恢复短期记忆上下文时，最多回注的消息条数 */
    public static final int MAX_CONTEXT_RESTORE_MESSAGES = 500;
    /** 调试接口单次返回长期记忆的最大条数 */
    public static final int MAX_DEBUG_MEMORY_MESSAGES = 200;
    /** 调试接口单次返回事实的最大条数 */
    public static final int MAX_DEBUG_FACTS = 50;
}