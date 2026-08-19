package com.litlebro.agent.router;

/**
 * 检索路由目标：决定本次请求是否下发向量检索工具，以及下发哪个。
 *
 * <p>只描述两个向量检索库（会话记忆 / 全局文档知识库）的暴露范围；
 * 附件读取工具（read_file/grep_file）不受本枚举控制，由附件注册表单独决定。
 */
public enum RetrievalTarget {

    /** 无需检索任何向量检索库（闲聊/创作/常识等；或问题引用的是随消息上传的附件） */
    NONE,

    /** 只检索当前会话记忆（search_memory） */
    MEMORY,

    /** 只检索全局文档知识库（search_document） */
    DOCUMENT,

    /** 同时检索会话记忆与文档知识库 */
    BOTH
}