package com.litlebro.agent.rag.parser;

import java.io.IOException;

/**
 * 文档解析策略接口，将上传的文件内容解析为可入库的纯文本。
 *
 * <p>每种文件格式对应一个独立实现（策略），通过 {@link DocumentParserFactory}
 * 按扩展名选择具体解析器。新增格式时只需实现本接口并注册到工厂，
 * 无需改动 {@code DocumentService} 业务逻辑。
 *
 * <p>约定：
 * <ul>
 *   <li>实现必须支持原始文件名（含扩展名），用于格式识别与日志</li>
 *   <li>解析失败应抛出 {@link IOException}，由上层统一处理</li>
 *   <li>返回的文本直接参与切块与向量化，尽量保留原始内容结构</li>
 * </ul>
 */
public interface DocumentParser {

    /**
     * 解析文件内容为纯文本。
     *
     * @param fileBytes 文件字节内容
     * @param filename  原始文件名（含扩展名）
     * @return 解析后的文本，可为多行；若内容为空返回空串
     * @throws IOException 解析失败时抛出
     */
    String parse(byte[] fileBytes, String filename) throws IOException;
}
