package com.litlebro.agent.rag.parser;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 纯文本类文档解析策略：支持 txt / md / json。
 *
 * <p>统一按 UTF-8 读取原始字节。json 文件直接以文本方式参与向量化检索，
 * 保留原始结构便于语义匹配。
 */
@Component
public class TextDocumentParser implements DocumentParser {

    @Override
    public String parse(byte[] fileBytes, String filename) {
        return new String(fileBytes, StandardCharsets.UTF_8);
    }
}
