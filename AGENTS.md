# AGENTS.md

面向 AI 编码代理（及新开发者）的项目初始化指南。

## 项目概述

`litlebro`（Spring AI Agent Demo）是一个基于 Spring Boot 3.4 + Spring AI 1.0.0-M6 的
AI Agent 项目。它通过 ChatClient 调用 OpenAI 兼容的 LLM，并实现了一套
**二层记忆架构**（短期/长期记忆）+ compaction 压缩机制和可插拔的工具调用机制。

- **语言/构建**：Java 17，Maven
- **主启动类**：`com.litlebro.agent.LitlebroApplication`
- **端口**：8080（可在 `application.yml` 中修改）

## 常用命令

```powershell
# 指定 JDK 17 编译
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"

# 编译
mvn compile

# 启动应用
mvn spring-boot:run

# 打包
mvn package
```

- 无独立 lint/checkstyle 配置；以 `mvn compile` 通过为验收标准。
- 单元测试位于 `src/test`，运行 `mvn test`。

## 目录结构

```
src/main/java/com/litlebro/agent/
├── LitlebroApplication.java        # 主启动类
├── controller/                    # REST 入口
│   ├── AgentController.java       # 对话 / 工具 / 会话 / 记忆查看
│   └── DocumentController.java    # 文档知识库上传 / 删除
├── service/                       # 业务核心
│   ├── AgentService.java          # 协调 LLM、工具、记忆、压缩
│   └── DocumentService.java       # 文档入库：解析 → 切块 → 向量化
├── common/
│   ├── Constant.java              # 常量统一管理（记忆类型 / 元数据键 / 上限）
│   └── SystemPrompt.java          # 全部提示词（对话/压缩/视觉/工具说明）
├── context/                       # 上下文管理
│   ├── ContextManager.java        # 上下文组装 + 溢出压缩（compactIfNeeded）
│   ├── CompressionService.java    # 对话历史压缩（支持增量压缩）
│   └── SessionContextHolder.java  # ThreadLocal 会话上下文（工具内取 sessionId）
├── config/                        # 全局 Bean 装配中心
│   └── AppConfig.java             # 装配 STM/LTM/会话/rag 缓存/附件/业务（@EnableAsync/@EnableScheduling）
├── memory/                        # 记忆模块
│   ├── MemoryStore.java           # 长期记忆存储抽象接口
│   ├── VectorMemoryStore.java     # 长期记忆向量存储封装
│   ├── LongTermMemoryService.java # 长期记忆业务服务（摘要存取 + 上下文构建）
│   ├── MessageCodec.java          # 对话消息与 AgentMessage 互转（序列化）
│   ├── model/AgentMessage.java    # 统一消息模型（含元数据、媒体、工具调用）
│   └── external/
│       └── RedisChatMemory.java   # 短期记忆（30 分钟 TTL）
├── session/                       # 会话状态（token 累积 / 轮次 / 模型）
│   ├── SessionManager.java        # 会话状态抽象接口
│   ├── AbstractSessionManager.java # 公共业务逻辑（token/轮次/模型合并）
│   ├── model/SessionMemory.java   # 会话状态模型
│   ├── local/
│   │   └── LocalSessionManager.java   # 本地内存实现（默认）
│   └── external/
│       └── RedisSessionManager.java  # Redis 实现（30 分钟 TTL）
├── tool/                          # LLM 可调用工具
│   ├── AgentTool.java             # 工具抽象接口
│   ├── ToolRegistry.java          # 工具注册表（收集所有 @Component AgentTool）
│   ├── DateTimeTool.java          # 日期时间工具
│   ├── SearchMemoryTool.java      # 会话记忆检索工具（search_memory）
│   ├── SearchDocumentTool.java    # 文档知识库检索工具（search_document）
│   ├── ReadFileTool.java          # 附件文件读取工具（read_file）
│   └── GrepFileTool.java          # 附件内容检索工具（grep_file）
├── attachment/                    # 附件直传模块
│   ├── AttachmentEntry.java       # 附件条目（fileId/归属/路径/过期）
│   ├── AttachmentRegistry.java    # 附件注册表抽象接口
│   ├── AttachmentStore.java       # 落盘/懒解析/删除/清理
│   ├── AttachmentCleanupTask.java # 定时清理任务（@Scheduled）
│   ├── local/LocalAttachmentRegistry.java     # 注册表本地内存实现（默认）
│   ├── external/RedisAttachmentRegistry.java  # 注册表 Redis 实现（TTL，重启不丢）
│   └── resolver/                  # 附件来源解析策略（base64/url/multipart）
│       ├── AttachmentResolver.java          # 解析策略接口
│       ├── AttachmentResolverFactory.java   # 解析策略工厂（按 type 路由）
│       ├── AttachmentInput.java             # 附件来源输入
│       ├── ResolvedAttachment.java          # 统一字节形态
│       ├── Base64AttachmentResolver.java
│       ├── UrlAttachmentResolver.java
│       └── MultipartAttachmentResolver.java
├── rag/                           # 文档处理核心
│   ├── DocumentParseCache.java    # 解析缓存抽象接口（文件哈希→文本）
│   ├── local/LocalDocumentParseCache.java # 解析缓存本地内存实现（默认）
│   ├── external/RedisDocumentParseCache.java # 解析缓存 Redis 实现（TTL）
│   ├── SemanticTextSplitter.java  # 语义切块器（embedding 相似度断点）
│   ├── DocumentSplitterFactory.java # 切块策略工厂（semantic / fixed）
│   └── parser/                    # 文件解析策略（按扩展名选择）
│       ├── DocumentParser.java    # 解析策略接口
│       ├── DocumentParserFactory.java # 解析策略工厂（txt/md/json/pdf/docx/xlsx/xls/图片）
│       ├── TextDocumentParser.java   # 纯文本解析（txt/md/json）
│       ├── PdfDocumentParser.java    # PDF 解析（文本层 + 图片页视觉描述）
│       ├── WordDocumentParser.java   # Word 解析（docx，Apache POI XWPF）
│       ├── ExcelDocumentParser.java  # Excel 解析（xlsx/xls，POI 流式 SAX）
│       ├── ImageDocumentParser.java  # 图片解析（png/jpg/jpeg/gif/webp/bmp）
│       └── VisionDescribeService.java # 图片视觉描述（dashscope qwen-vl）
├── dto/                           # 请求/响应结构
│   ├── ChatRequest.java           # question + sessionId（无 userId）
│   ├── ChatResponse.java
│   ├── ErrorResponse.java
│   └── DocumentIngestResult.java  # 文档入库结果（docId / source / chunkCount）
└── exception/GlobalExceptionHandler.java  # 全局异常处理
```

## 记忆架构

二层记忆由两个独立配置项分别切换，互不影响：

| 记忆层 | 配置项 | 内存实现 | 外部实现 |
| --- | --- | --- | --- |
| 短期（ChatMemory） | `app.memory.stm.type` | `InMemoryChatMemory`（`local`） | `RedisChatMemory`（`redis`，30 分钟 TTL） |
| 长期（VectorStore） | `app.memory.ltm.type` | `SimpleVectorStore`（`local`） | `MilvusVectorStore`（`milvus`） |

**记忆按 sessionId 隔离**，每个会话有独立的记忆空间。

- 会话状态（SessionManager）、文档解析缓存（DocumentParseCache）与附件注册表（AttachmentRegistry）
  均为「接口 + 双实现」：`local`（本地内存）与 `redis`/`milvus`（外部存储），由配置决定加载哪个实现，
  可随时新增存储后端
- 存储实现互不耦合，可自由组合（如 STM 用 redis + LTM 用 local + 附件注册表用 redis）

### Compaction 压缩机制（借鉴 opencode）

每次对话后检查会话累积的输入 Token（模型返回的 usage）是否超出模型窗口的 75%，
超出则触发增量压缩：

1. 保留最近 6 条消息原文
2. 旧消息 + 上一次压缩摘要 → LLM 压缩 → 新摘要存入长期记忆（`CATEGORY_SUMMARY`）
3. 重建 ChatMemory：摘要 SystemMessage + 最近 6 条原文
4. 重置会话当前 Token 占用，继续后续对话
5. 下次压缩时传入旧摘要做增量，不重复压缩同一段历史

压缩提示词是任务导向的（"做了什么、决定了什么、什么还没做"），而非提取个人事实。

## API 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agent/chat` | 对话，body: `{"question":"...", "sessionId":"..."}` |
| GET | `/api/agent/tools` | 工具列表 |
| GET | `/api/agent/session/{sessionId}` | 会话轮次计数 |
| GET | `/api/agent/memory/{sessionId}` | 会话长期记忆（摘要 + 事实） |
| POST | `/api/rag/document` | 文档入库，multipart 字段名 `file`（txt/md/json/pdf/docx/xlsx/xls/图片） |
| DELETE | `/api/rag/document/{docId}` | 按文档 ID 删除全部切块 |

## 配置说明

关键配置在 `src/main/resources/application.yml`，均支持环境变量覆盖：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| LLM API Key | `OPENAI_API_KEY` | `your-api-key-here` |
| LLM Base URL | `OPENAI_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode` |
| 模型 | — | `qwen3.8-max` |
| Redis 地址 | `REDIS_HOST` / `REDIS_PORT` | `localhost:6379` |
| Milvus 地址 | `MILVUS_HOST` / `MILVUS_PORT` | `localhost:19530` |
| 短期记忆类型 | `APP_MEMORY_STM_TYPE`（`app.memory.stm.type`） | `local` |
| 长期记忆类型 | `APP_MEMORY_LTM_TYPE`（`app.memory.ltm.type`） | `local` |
| 上下文窗口大小 | `APP_MEMORY_CONTEXT_MAX_TOKENS`（`app.memory.context.max-tokens`） | `128000` |
| 语义检索相似度阈值 | `APP_MEMORY_VECTOR_SIMILARITY_THRESHOLD`（`app.memory.vector.similarity-threshold`） | `0.2` |
| 文档检索二次过滤阈值 | `APP_RAG_MIN_SCORE`（`app.rag.min-score`） | `0.35` |
| 记忆检索二次过滤阈值 | `APP_MEMORY_MIN_SCORE`（`app.memory.min-score`） | `0.35` |
| 单文档解析文本长度上限 | `APP_RAG_MAX_TEXT_LENGTH`（`app.rag.max-text-length`） | `3000000` |
| 图片视觉描述开关 | `APP_RAG_VISION_ENABLED`（`app.rag.vision.enabled`） | `false` |
| 视觉描述模型 | `APP_RAG_VISION_MODEL`（`app.rag.vision.model`） | `qwen-vl-plus` |
| 视觉描述 API Key | `APP_RAG_VISION_API_KEY`（`app.rag.vision.api-key`） | 复用对话 API Key |
| 视觉描述服务地址 | `APP_RAG_VISION_BASE_URL`（`app.rag.vision.base-url`） | DashScope 默认 |
| PDF 渲染 DPI | `APP_RAG_VISION_PDF_DPI`（`app.rag.vision.pdf-dpi`） | `150` |
| 文档解析缓存类型 | `APP_RAG_CACHE_TYPE`（`app.rag.cache.type`） | `local`（`local`/`redis`） |
| 文档解析缓存 TTL | `APP_RAG_CACHE_PARSE_TTL_HOURS`（`app.rag.cache.parse-ttl-hours`） | `24` |
| 附件注册表类型 | `APP_ATTACHMENT_REGISTRY_TYPE`（`app.attachment.registry.type`） | `local`（`local`/`redis`） |
| 附件存活天数 | `APP_ATTACHMENT_TTL_DAYS`（`app.attachment.ttl-days`） | `7` |
| 切块策略 | `APP_RAG_SPLITTER_STRATEGY`（`app.rag.splitter.strategy`） | `semantic` |
| 语义切块断点模式 | `APP_RAG_SPLITTER_SEMANTIC_BREAKPOINT_MODE` | `percentile` |
| 语义断点百分位 | `APP_RAG_SPLITTER_SEMANTIC_PERCENTILE` | `95` |
| 语义聚合阈值 | `APP_RAG_SPLITTER_SEMANTIC_THRESHOLD` | `0.7` |
| 语义断点缓冲窗口 | `APP_RAG_SPLITTER_SEMANTIC_BUFFER_SIZE` | `3` |
| 语义切块最大字符数 | `APP_RAG_SPLITTER_SEMANTIC_MAX_CHUNK` | `800` |
| 固定切块 token 数 | `APP_RAG_SPLITTER_FIXED_CHUNK_SIZE` | `500` |

### RAG 文档知识库

- 文档按 `category == document` 全局共享（不绑定 sessionId），会话记忆按 sessionId 隔离
- 上传：`POST /api/rag/document`（multipart `file`），支持 txt/md/json/pdf（PDFBox）/docx/xlsx/xls（Apache POI）/png/jpg/jpeg/gif/webp/bmp（视觉描述）
- PDF 解析走策略模式：优先提取文本层；图片型页面渲染为图片后由 dashscope 视觉模型（qwen-vl）描述内容，`app.rag.vision.enabled` 控制
- **图片内容识别**：图片型 PDF 页面与直接上传的图片文件，均由 qwen-vl 将画面描述为文字后以纯文本入库；检索与回答全程走纯文本链路，不依赖视觉模型也能回答
- **大文件防护**：xlsx 走 SAX 流式读取避免整本加载；解析文本超过 `app.rag.max-text-length` 自动截断，防止内存溢出
- **解析缓存**：以文件内容 SHA-256 为 key 缓存解析文本（`DocumentParseCache`），重复上传同一文件直接跳过解析——图片型文档只在首次上传调用一次视觉模型，省 token；存储后端由 `app.rag.cache.type` 显式切换（`local` 本地内存 / `redis` Redis + TTL），与短期记忆配置互不影响
- 检索：由 LLM 按需调用两个工具——`search_document`（查文档库）、`search_memory`（查本会话记忆）
- 切块策略 `semantic`（embedding 相似度断点，默认百分位 95、buffer 3、max-chunk 800）或 `fixed`（固定 token 数），由 `app.rag.splitter.strategy` 切换
- 语义切块复用对话同一个 embedding 模型，保证切块/检索/对话向量空间一致

## 开发约定

- **编码**：源码含中文注释，文件统一使用 UTF-8（无 BOM）。
- **注释语言**：javadoc 与行内注释均用中文书写。
- **本地实现位于 `xxx/local`，外部实现位于 `xxx/external`，共享抽象（接口/配置/条件）
  留在根包**；新增存储实现时遵循此布局（如 `SessionManager`、`DocumentParseCache`）。
- 新增功能建议优先遵循现有二层记忆 + 工具回调 + compaction 的模式。
- 切勿将 API Key 等机密写入仓库或提交。