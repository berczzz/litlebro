package com.litlebro.agent.tool;

/**
 * 工具元数据常量，统一维护全部 LLM 可调用工具的说明与参数描述。
 *
 * <p>与 {@code SystemPrompt} 分离：这里只放工具相关的描述文本
 * （供 {@code @Tool}/{@code @ToolParam} 注解与 {@link AgentTool} 元数据使用），
 * 系统提示词等与工具无关的提示词仍留在 {@code SystemPrompt}。
 *
 * <p>新增工具时：实现 {@link AgentTool} + 提供 {@code @Tool} 方法，
 * 描述文本统一放本类常量并引用。
 */
public final class ToolDescriptions {

    private ToolDescriptions() {
    }

    // ==================== search_memory 会话记忆检索 ====================

    public static final String SEARCH_MEMORY_NAME = "会话记忆检索";
    public static final String SEARCH_MEMORY_DESC = "检索当前会话的历史记忆，当用户询问此前对话内容或想回忆本会话聊过什么时使用";
    public static final String SEARCH_MEMORY_TOOL = "检索当前会话的历史记忆，返回与本会话相关的过往对话内容。当用户询问此前聊过什么、做过什么决定时调用";
    public static final String SEARCH_MEMORY_PARAM = "检索描述：将用户问题改写为完整的查询描述（包含核心对象与要查找的信息），不要只截取几个关键词";

    // ==================== search_document 文档知识库检索 ====================

    public static final String SEARCH_DOCUMENT_NAME = "文档知识库检索";
    public static final String SEARCH_DOCUMENT_DESC = "检索已上传的文档知识库，当用户询问文档内容相关问题（项目文档、说明手册等）时使用";
    public static final String SEARCH_DOCUMENT_TOOL = "检索已上传的文档知识库，返回与检索词相关的文档内容片段及其来源文件名。当用户询问文档/资料内容时调用";
    public static final String SEARCH_DOCUMENT_PARAM = "检索描述：将用户问题改写为完整的查询描述（包含核心对象与要查找的信息），不要只截取几个关键词。例如问'result里有什么字段'应写'JSON 响应中 result 对象包含哪些字段'";

    // ==================== date_time 日期时间工具 ====================

    public static final String DATE_TIME_NAME = "日期时间工具";
    public static final String DATE_TIME_DESC = "获取当前时间、判断星期几、计算日期差、日期偏移";
    public static final String DATE_TIME_CURRENT_DATETIME = "获取当前系统日期和时间，精确到秒，同时返回所在时区";
    public static final String DATE_TIME_CURRENT_DATE = "获取当前系统日期，不含时间部分";
    public static final String DATE_TIME_DAY_OF_WEEK = "根据给定的日期字符串，判断该日期是星期几";
    public static final String DATE_TIME_DAY_OF_WEEK_PARAM = "日期字符串，格式为 yyyy-MM-dd，例如 2026-08-11";
    public static final String DATE_TIME_DAYS_BETWEEN = "计算两个日期之间相差的天数，返回绝对值";
    public static final String DATE_TIME_DATE_PARAM = "第一个日期，格式 yyyy-MM-dd";
    public static final String DATE_TIME_DATE2_PARAM = "第二个日期，格式 yyyy-MM-dd";
    public static final String DATE_TIME_ADD_DAYS = "根据给定的日期和偏移天数，计算偏移后的日期。正数表示未来，负数表示过去";
    public static final String DATE_TIME_ADD_DAYS_DATE_PARAM = "基准日期，格式 yyyy-MM-dd";
    public static final String DATE_TIME_ADD_DAYS_DAYS_PARAM = "偏移天数，正数表示未来，负数表示过去";
}