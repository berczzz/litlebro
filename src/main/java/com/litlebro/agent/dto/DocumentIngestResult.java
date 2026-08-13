package com.litlebro.agent.dto;

/**
 * 文档入库结果。
 *
 * @param docId      文档唯一标识（UUID，用于后续删除）
 * @param source     原始文件名
 * @param chunkCount 切块数量
 */
public record DocumentIngestResult(
        String docId,
        String source,
        int chunkCount
) {
}
