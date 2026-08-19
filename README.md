

# litlebro — 小老弟

一个基于 **Spring Boot 3.4 + Spring AI 1.0.0-M6** 的 AI Agent 项目（开箱即用）。通过 ChatClient 调用 OpenAI 兼容的 LLM，实现了一套**二层记忆架构**（短期/长期记忆）+ **compaction 压缩机制**和**可插拔的工具调用机制**。

## 特性

- **对话式 Agent**：REST API 调用，支持多会话隔离（sessionId）
- **二层记忆架构**：
  - 短期记忆（ChatMemory）— 维护最近对话历史
  - 长期记忆（向量库）— 持久化消息与压缩摘要
- **Compaction 压缩机制**：
  - LLM 调用后置检查 token 占用，超阈值（模型窗口 75%）自动触发增量压缩
  - 保留最近 6 条消息原文，更早的历史压缩为摘要存入长期记忆
  - 压缩时传入旧摘要做增量，不重复压缩同一段历史
- **可插拔工具**：日期时间、会话记忆检索、文档知识库检索、附件读取/检索，LLM 按需自动调用
- **工具禁用管理**：每个工具启动时生成稳定 ID（类名，跨重启不变），可按 ID 禁用/启用，禁用后不再下发给大模型；禁用状态存储可切换内存 / Redis
- **技能（Skills）模块**：本地目录技能包，对话按请求 `skillIds` 启用并**自动累加记录**到会话（无过期），LLM 可渐进式加载说明、执行捆绑脚本、读取包内参考文件（默认关闭）
- **MCP（Model Context Protocol）Client 模块**：注册 stdio / SSE 类型的 MCP 服务器，按请求 `mcpServerIds` 启用并**自动累加记录**到会话，工具经 `{serverId}_` 前缀化后并入统一工具池，LLM 可直接调用（默认关闭）
- **RAG 文档知识库**：上传 txt/md/json/pdf/docx/xlsx/xls/csv/图片 → 语义/固定切块 → 向量化，LLM 按需检索
- **附件直传**：对话时可直接携带文件（base64 / URL / multipart），文档类附件懒解析后由 LLM 用工具读取，到期自动清理
- **两级检索过滤**：向量库宽召回（低阈值）+ 工具层相似度二次过滤（去噪）
- **存储模式可切换**：内存实现（默认，开箱即用）/ Redis + Milvus 外部实现
- **Token 统计**：以模型返回的 usage 为准，按会话累积统计

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Java | 17 |
| Spring Boot | 3.4.5 |
| Spring AI | 1.0.0-M6 |
| 构建工具 | Maven |
| 向量库 | Milvus（可回退 SimpleVectorStore） |
| 缓存/短期记忆 | Redis（可回退内存） |
| 文档解析 | PDFBox（PDF）/ Apache POI（docx/xlsx/xls）/ 手写 CSV（csv）/ dashscope qwen-vl（图片视觉描述） |

## 快速开始

### 1. 环境要求

- JDK 17
- Maven 3.6+
- （可选）Redis、Milvus

### 2. 配置

编辑 `src/main/resources/application.yml`，设置 LLM 相关参数（均支持环境变量覆盖）：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| API Key | `OPENAI_API_KEY` | 需自行填写 |
| Base URL | `OPENAI_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode` |
| 模型 | — | `qwen3.8-max` |

> 本项目默认对接阿里云 DashScope 的 OpenAI 兼容模式，也可改为任意兼容网关。注意：Spring AI 会自动拼接 `/v1/{endpoint}`，base-url **不要带尾部 `/v1`**。

### 3. 编译运行

```powershell
# 指定 JDK 17 编译
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"

# 编译
mvn compile

# 启动
mvn spring-boot:run
```

应用默认运行在 `http://localhost:8080`。

## 快速测试

```bash
# 对话
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "今天几号？", "sessionId": "demo-1", "skillIds": ["demo-helper"], "mcpServerIds": ["echo"]}'

# 工具列表（含 id/name/description/enabled）
curl http://localhost:8080/api/agent/tools

# 按 ID 禁用某个工具（禁用后不再下发给大模型）
curl -X POST http://localhost:8080/api/agent/tools/{toolId}/disable

# 恢复工具
curl -X POST http://localhost:8080/api/agent/tools/{toolId}/enable

# 会话统计（token、轮次）
curl http://localhost:8080/api/agent/session/demo-1

# 长期记忆（摘要）
curl http://localhost:8080/api/agent/memory/demo-1

# 上传文档到知识库（txt/md/json/pdf/docx/xlsx/xls/csv/图片）
curl -X POST http://localhost:8080/api/rag/document \
  -F "file=@/path/to/doc.txt"

# 删除文档（docId 为上传返回值）
curl -X DELETE http://localhost:8080/api/rag/document/{docId}

# 注册 MCP 服务器（stdio 类型，node 启动子进程；模块需开启 app.mcp.enabled=true）
curl -X POST http://localhost:8080/api/agent/mcp/servers \
  -H "Content-Type: application/json" \
  -d '{"serverId":"echo","name":"Echo","description":"echo server","transport":"stdio","command":"node","args":["echo-mcp-server.js"]}'

# MCP 服务器列表
curl http://localhost:8080/api/agent/mcp/servers

# 验证接入：连接并列出该服务器工具
curl http://localhost:8080/api/agent/mcp/servers/echo/tools
```

## API 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agent/chat` | 对话，body: `{"question":"...", "sessionId":"...", "skillIds":["..."], "mcpServerIds":["..."], "attachments":[{...}]}`（skillIds / mcpServerIds 可选，未注册/未启用返回 400；attachments 为 base64/URL） |
| POST | `/api/agent/chat/multipart` | 对话（multipart），字段 `question`、`sessionId`、`skillIds`、`mcpServerIds`（逗号分隔）、`files` |
| POST | `/api/agent/chat/stream` | 流式对话（SSE），事件：`start`/`reasoning`/`tool_call`/`tool_result`/`content`/`error`/`done`；记忆行为与 `/chat` 一致 |
| POST | `/api/agent/chat/stream/multipart` | 流式对话（multipart），同上 |
| GET | `/api/agent/tools` | 工具列表（含 id/name/description/enabled，已禁用工具仍展示 enabled=false） |
| POST | `/api/agent/tools/{toolId}/disable` | 按工具 ID 禁用（禁用后不再下发给大模型） |
| POST | `/api/agent/tools/{toolId}/enable` | 按工具 ID 恢复 |
| GET | `/api/agent/session/{sessionId}` | 会话统计（token 累积、轮次、模型） |
| GET | `/api/agent/memory/{sessionId}` | 会话长期记忆（摘要 + 事实） |
| POST | `/api/agent/skills` | 注册技能（校验技能包目录 + SKILL.md，已存在返回 409），body 为 SkillDefinition JSON |
| GET | `/api/agent/skills` | 技能列表 |
| DELETE | `/api/agent/skills/{skillId}` | 删除技能（含全部会话技能记录） |
| DELETE | `/api/agent/skills/records/{sessionId}` | 清空会话已记录的技能（无过期，需显式清空） |
| POST | `/api/agent/mcp/servers` | 注册 MCP 服务器（校验 transport/字段，已存在返回 400），body 为 McpServerConfig JSON（serverId/name/description/transport/command/args/env/url/enabled/global） |
| GET | `/api/agent/mcp/servers` | MCP 服务器列表 |
| GET | `/api/agent/mcp/servers/{serverId}/tools` | 连接（懒）并列出该服务器工具，用于验证接入 |
| POST | `/api/agent/mcp/servers/{serverId}/enable` | 启用 MCP 服务器 |
| POST | `/api/agent/mcp/servers/{serverId}/disable` | 禁用 MCP 服务器（立即关闭连接，会话记录保留） |
| DELETE | `/api/agent/mcp/servers/{serverId}` | 删除 MCP 服务器（含会话记录，关闭连接） |
| DELETE | `/api/agent/mcp/records/{sessionId}` | 清空会话已记录的 MCP 服务器（无过期，需显式清空） |
| POST | `/api/rag/document` | 文档入库，multipart 字段名 `file`（txt/md/json/pdf/docx/xlsx/xls/csv/图片） |
| DELETE | `/api/rag/document/{docId}` | 按文档 ID 删除全部切块 |

## 记忆架构

短期记忆（STM）与长期记忆（LTM）**独立配置**，互不影响：

| 记忆层 | 配置项 | 内存实现 | 外部实现 |
| --- | --- | --- | --- |
| 短期（ChatMemory） | `app.memory.stm.type` | `InMemoryChatMemory`（`local`） | `RedisChatMemory`（`redis`，30 分钟 TTL） |
| 长期（VectorStore） | `app.memory.ltm.type` | `SimpleVectorStore`（`local`） | `MilvusVectorStore`（`milvus`） |
| 会话状态（SessionManager） | `app.memory.stm.type` | `LocalSessionManager`（`local`） | `RedisSessionManager`（`redis`，30 分钟 TTL） |
| 文档解析缓存 | `app.rag.cache.type` | `LocalDocumentParseCache`（`local`） | `RedisDocumentParseCache`（`redis`，24h TTL） |
| 附件注册表 | `app.attachment.registry.type` | `LocalAttachmentRegistry`（`local`，重启丢失） | `RedisAttachmentRegistry`（`redis`，TTL 对齐附件过期） |
| 工具禁用状态 | `app.tool.store.type` | `LocalToolDisabledStore`（`local`，重启丢失） | `RedisToolDisabledStore`（`redis`，无 TTL） |
| 技能存储 | `app.skill.store.type` | `LocalSkillStore`（`local`，重启丢失） | `RedisSkillStore`（`redis`，记录无 TTL） |
| MCP 服务器存储 | `app.mcp.store.type` | `LocalMcpServerStore`（`local`，重启丢失） | `RedisMcpServerStore`（`redis`，记录无 TTL） |

**记忆按 sessionId 隔离**，每个会话有独立记忆空间。

存储实现互不耦合，可自由组合。例如：

```yaml
app:
  memory:
    stm:
      type: redis      # 短期记忆用 Redis
    ltm:
      type: local      # 长期记忆用进程内向量库
```

### 配置说明

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| 短期记忆类型 | `APP_MEMORY_STM_TYPE`（`app.memory.stm.type`） | `local` |
| 长期记忆类型 | `APP_MEMORY_LTM_TYPE`（`app.memory.ltm.type`） | `local` |
| 上下文窗口大小 | `APP_MEMORY_CONTEXT_MAX_TOKENS`（`app.memory.context.max-tokens`） | `128000` |
| 向量检索相似度阈值 | `APP_MEMORY_VECTOR_SIMILARITY_THRESHOLD`（`app.memory.vector.similarity-threshold`） | `0.2` |
| 记忆检索二次过滤阈值 | `APP_MEMORY_MIN_SCORE`（`app.memory.min-score`） | `0.35` |
| 文档检索二次过滤阈值 | `APP_RAG_MIN_SCORE`（`app.rag.min-score`） | `0.35` |
| 切块策略 | `APP_RAG_SPLITTER`（`app.rag.splitter.strategy`） | `semantic` |
| 单文档解析文本长度上限 | `APP_RAG_MAX_TEXT_LENGTH`（`app.rag.max-text-length`） | `3000000` |
| 图片视觉描述开关 | `APP_RAG_VISION_ENABLED`（`app.rag.vision.enabled`） | `false` |
| 视觉描述模型 | `APP_RAG_VISION_MODEL`（`app.rag.vision.model`） | `qwen-vl-plus` |
| 视觉描述 API Key | `APP_RAG_VISION_API_KEY`（`app.rag.vision.api-key`） | 复用对话 API Key |
| 视觉描述服务地址 | `APP_RAG_VISION_BASE_URL`（`app.rag.vision.base-url`） | DashScope 默认 |
| PDF 渲染 DPI | `APP_RAG_VISION_PDF_DPI`（`app.rag.vision.pdf-dpi`） | `150` |
| 文档解析缓存类型 | `APP_RAG_CACHE_TYPE`（`app.rag.cache.type`） | `local`（`local`/`redis`） |
| 流式思考开关 | `APP_STREAM_ENABLE_THINKING`（`app.stream.enable-thinking`） | `false` |
| 文档解析缓存 TTL | `APP_RAG_CACHE_PARSE_TTL_HOURS`（`app.rag.cache.parse-ttl-hours`） | `24` |
| 文档解析缓存容量上限 | `APP_RAG_CACHE_MAX_ENTRIES`（`app.rag.cache.max-entries`） | `100`（本地缓存条数上限，LRU 淘汰，防无界缓存耗尽内存） |
| 附件注册表类型 | `APP_ATTACHMENT_REGISTRY_TYPE`（`app.attachment.registry.type`） | `local`（`local`/`redis`） |
| 附件存活天数 | `APP_ATTACHMENT_TTL_DAYS`（`app.attachment.ttl-days`） | `7` |
| 工具禁用状态存储类型 | `APP_TOOL_STORE_TYPE`（`app.tool.store.type`） | `local`（`local`/`redis`） |
| 技能模块开关 | `APP_SKILL_ENABLED`（`app.skill.enabled`） | `false`（关闭时技能服务/工具/Controller 不加载） |
| 技能包根目录 | `APP_SKILL_DIR`（`app.skill.dir`） | `./data/skills` |
| 技能存储类型 | `APP_SKILL_STORE_TYPE`（`app.skill.store.type`） | `local`（`local`/`redis`） |
| 技能脚本执行开关 | `APP_SKILL_EXEC_ENABLED`（`app.skill.exec.enabled`） | `false`（开启后 exec_skill 才允许执行） |
| 技能脚本超时 | `APP_SKILL_EXEC_TIMEOUT_MS`（`app.skill.exec.timeout-ms`） | `30000`（超时强杀） |
| 技能执行输出上限 | `APP_SKILL_EXEC_OUTPUT_MAX_CHARS`（`app.skill.exec.output-max-chars`） | `8192`（字符，超限截断） |
| 技能解释器白名单 | `APP_SKILL_EXEC_INTERPRETER_ALLOW_LIST`（`app.skill.exec.interpreter-allow-list`） | `python3,node,bash,powershell` |
| MCP 模块开关 | `APP_MCP_ENABLED`（`app.mcp.enabled`） | `false`（关闭时 MCP 服务/工具/Controller 不加载） |
| MCP 服务器存储类型 | `APP_MCP_STORE_TYPE`（`app.mcp.store.type`） | `local`（`local`/`redis`） |
| MCP 请求超时 | `APP_MCP_REQUEST_TIMEOUT_MS`（`app.mcp.request-timeout-ms`） | `15000`（毫秒，initialize/listTools/callTool） |
| MCP 工具缓存 TTL | `APP_MCP_TOOL_CACHE_TTL_SECONDS`（`app.mcp.tool-cache-ttl-seconds`） | `60`（秒，过期重新 listTools 刷新） |
| Redis 地址 | `REDIS_HOST` / `REDIS_PORT` | `localhost:6379` |
| Milvus 地址 | `MILVUS_HOST` / `MILVUS_PORT` | `localhost:19530` |

### Compaction 压缩流程

每次对话后检查会话累积的输入 token 是否超出模型窗口的 75%，超出则触发增量压缩：

1. 保留最近 **6 条消息**原文
2. 更早的旧消息 + 上一次压缩摘要 → LLM 压缩 → 新摘要（`CATEGORY_SUMMARY`）
3. 新摘要存入长期记忆，并作为 SystemMessage 重新注入短期记忆
4. 重置当前 token 占用，继续后续对话
5. 下次压缩时传入旧摘要做增量，不重复压缩同一段历史

压缩提示词是任务导向的（"做了什么、决定了什么、什么还没做"），而非提取个人事实。

### 检索过滤机制

检索采用**两级过滤**，兼顾召回率与精度：

| 层级 | 位置 | 阈值 | 作用 |
| --- | --- | --- | --- |
| 向量库宽召回 | `VectorMemoryStore` | `similarity-threshold: 0.2` | 低门槛保证相关片段不丢 |
| 工具层精过滤 | `SearchDocumentTool` / `SearchMemoryTool` | `min-score: 0.35` | 丢弃弱相关噪声，按分数降序返回 |

两个阈值独立可调，分别通过 `app.memory.vector.similarity-threshold`、`app.rag.min-score`、`app.memory.min-score` 配置。

## RAG 文档知识库

- 文档按 `category == document` **全局共享**（不绑定 sessionId），会话记忆按 sessionId 隔离
- 上传：`POST /api/rag/document`（multipart `file`），支持 txt/md/json/pdf（PDFBox）/docx/xlsx/xls（Apache POI）/csv（手写 RFC 4180）/png/jpg/jpeg/gif/webp/bmp（视觉描述）
- PDF 解析走策略模式：优先提取文本层；图片型页面渲染为图片后由 dashscope 视觉模型（qwen-vl）**描述内容**入库，`app.rag.vision.enabled` 控制
- **图片内容识别**：图片型 PDF 页面与直接上传的图片文件，均由 qwen-vl 将画面描述为文字后以纯文本入库；多模态能力只在入库时使用一次，检索与回答全程走纯文本链路（不依赖视觉模型也能回答）
- **大文件防护**：上传单文件上限 50MB（`APP_MAX_FILE_SIZE`）；xlsx 走 SAX 流式读取避免整本加载；解析文本超过 `app.rag.max-text-length` 自动截断，防止内存溢出
- **解析缓存**：以文件内容 SHA-256 为 key 缓存解析文本，重复上传同一文件直接跳过解析——图片型文档只在首次上传调用一次视觉模型，省 token；存储后端由 `app.rag.cache.type` 显式切换（`local` 本地内存 / `redis` Redis + TTL），与短期记忆配置互不影响
- 检索：由 LLM 按需调用两个工具——`search_document`（查文档库）、`search_memory`（查本会话记忆）
- 切块策略 `semantic`（embedding 相似度断点，默认百分位 95、buffer 3、max-chunk 800；段数超过 500 跳过向量化直接固定切分，防大表格逐行成段导致海量 embedding 请求）
   或 `fixed`（固定 token 数），由 `app.rag.splitter.strategy` 切换
- 语义切块复用对话同一个 embedding 模型，保证切块/检索/对话向量空间一致

## 附件直传

对话时可携带文件附件，由 LLM 按需读取内容：

- **三种来源**：JSON `attachments`（base64 / URL）、`POST /api/agent/chat/multipart`（multipart `files`）
- **图片**：直接转为多模态 Media 随对话传模型，不落盘
- **文档/文本**：落盘登记 fileId，写入系统提示词告知 LLM 用 `read_file` / `grep_file` 工具读取；PDF/Word/Excel 懒解析为纯文本
- **懒解析**：首次被工具读取时才解析（复用 `DocumentParserFactory`），解析结果 txt 与源文件一同落盘缓存
- **会话隔离**：附件归属创建它的 sessionId，工具按 fileId 校验归属后才允许读取
- **自动清理**：定时任务（默认每小时）扫描注册表，删除过期（默认 7 天）的原文件 + 懒解析 txt
- **注册表可切换**：`app.attachment.registry.type` 为 `local`（默认，内存）/ `redis`（Redis + TTL，重启不丢），与其余存储配置互不影响

## 工具调用

内置工具（`com.litlebro.agent.tool`），LLM 会根据问题自动选择：

| 工具 | 方法名 | 用途 |
| --- | --- | --- |
| `DateTimeTool` | `getCurrentDate` 等 | 获取当前日期/时间、日期计算 |
| `SearchMemoryTool` | `search_memory` | 检索当前会话历史记忆（按 sessionId 隔离） |
| `SearchDocumentTool` | `search_document` | 检索全局文档知识库 |
| `ReadFileTool` | `read_file` | 读取附件文本内容（按 fileId 按行读取） |
| `GrepFileTool` | `grep_file` | 在附件中检索关键词所在行 |
| `LoadSkillTool` | `load_skill` | 读取技能包 SKILL.md（随技能模块开关加载） |
| `ExecSkillTool` | `exec_skill` | 执行技能捆绑脚本（随技能模块开关加载） |
| `ReadSkillFileTool` | `read_skill_file` | 读取技能包内参考文件（随技能模块开关加载） |
| MCP 服务器工具 | `{serverId}_工具名` | 由 MCP 模块按需接入（随模块开关加载，服务器连接后出现在工具列表） |

新增工具只需实现 `AgentTool` 接口（含默认 `id()`，取类名首字母小写，跨重启稳定）并声明为 Bean，即可被 `ToolRegistry` 自动收集；MCP 工具则经前缀化并入同一工具池。

### 工具禁用管理

每个工具启动时生成稳定 ID（类名，跨重启不变），可按 ID 禁用/启用：

- `POST /api/agent/tools/{toolId}/disable`：禁用后该工具从给大模型的工具列表剔除（阻塞式 `ChatClient.tools` 与流式 Schema 共用同一过滤）
- `POST /api/agent/tools/{toolId}/enable`：恢复
- 禁用状态存储由 `app.tool.store.type` 切换：`local`（默认，内存，重启丢失）/ `redis`（Hash `agent:tool:disabled`，无 TTL，重启不丢）

## 技能（Skills）模块

本地目录技能包模型，运行时零下载，默认关闭（`app.skill.enabled=true` 开启）：

- **技能包布局**：`app.skill.dir/{skillId}/` 含 SKILL.md（必填，load_skill 读取）+ scripts/（exec_skill 执行）+ references/、assets/（read_skill_file 读取）；注册只声明元数据并校验目录存在且含 SKILL.md
- **按请求启用 + 自动记录**：对话请求携带 `skillIds`，应用层校验后**累加记录**到该会话（无过期时间）；可用技能 = `global 技能 ∪ 会话已记录技能 ∪ 请求 skillIds`；同一会话后续请求无需再携带；未注册/未启用的 skillId 直接返回 400
- **显式清空**：`DELETE /api/agent/skills/records/{sessionId}` 清空会话技能记录，清空后仅剩 global 技能
- **静态预注册**：`app.skill.skills` 配置中的技能启动时入库（同名跳过）；`global: true` 的技能所有请求直接可用，无需记录
- **工具过滤**：本次请求无可用技能时，三个技能工具从 LLM 工具列表剔除；工具内鉴权基于当前请求可用名单，未启用的技能调用被拒绝
- **解释器判定三层**：SkillDefinition.interpreterMap 显式覆盖 → 扩展名映射（py→python3/js→node/sh→bash/ps1→powershell；exe/cmd/bat/jar 直接执行）→ shebang 兜底 → 结果须在白名单；Windows 自动映射 python3→python
- **安全边界**：skillId/scriptName 拒绝路径分隔符与 `..`；技能目录归一化后必须落在根目录内；脚本执行开关 `app.skill.exec.enabled` 默认关闭；v1 无沙箱（脚本以应用同权限运行）

## MCP（Model Context Protocol）Client 模块

注册 MCP 服务器并让 LLM 直接调用其工具，默认关闭（`app.mcp.enabled=true` 开启）：

- **服务器注册**：REST 注册 stdio / SSE 两种传输的服务器；stdio 启动本地子进程（command + args + env），SSE 连接远端 url；注册只持久化配置，不建立连接（**懒连接**，首次会话使用该服务器工具时才连，stdio 同时拉起子进程）
- **按请求启用 + 自动记录**：对话请求携带 `mcpServerIds`，应用层校验后**累加记录**到该会话（无过期时间）；可用服务器 = `global 服务器 ∪ 会话已记录服务器 ∪ 请求 mcpServerIds`；同一会话后续请求无需再携带；未注册/未启用的 serverId 直接返回 400（流式端点在控制器同步校验）
- **显式清空**：`DELETE /api/agent/mcp/records/{sessionId}` 清空会话记录，清空后仅剩 global 服务器
- **前缀化并入统一工具池**：工具经 `{serverId}_工具名` 前缀命名后并入统一工具池，出现在 `GET /api/agent/tools`，与内置工具共用 `ToolDisabledStore` 按前缀 ID 禁用/启用
- **统一工具集（阻塞式与流式一致）**：`ToolResolver` 一次解析出本次请求的统一 `List<ToolCallback>`（内置/技能经 `ToolCallbacks.from` 反射转换 + MCP 回调追加），阻塞式走 `ChatClient.tools(List)`、流式走 `StreamingToolExecutor.beginRequest(List)`，两链路工具集完全一致
- **连接管理**：每服务器一把锁防并发首用重复拉起子进程；工具列表按 `app.mcp.tool-cache-ttl-seconds` 刷新；删除/禁用服务器或应用退出时关闭连接（stdio 结束子进程）；连接失败仅跳过该服务器，不影响其他服务器与本请求
- **SDK 依赖**：`spring-ai-mcp`（BOM 管 1.0.0-M6）传递引入 MCP Java SDK `io.modelcontextprotocol.sdk:mcp:0.7.0`；stdio 用 `StdioClientTransport(ServerParameters)`，SSE 用 `HttpClientSseClientTransport(url)`（0.7.0 不支持 SSE 请求头）

## 项目结构

```
src/main/java/com/litlebro/agent/
├── LitlebroApplication.java        # 主启动类
├── controller/                     # REST 入口
│   ├── AgentController.java        # 对话（阻塞式 + 流式 SSE，/api/agent/chat*）
│   ├── ToolController.java         # 工具列表 / 按 ID 禁用与恢复（/api/agent/tools）
│   ├── SessionController.java      # 会话状态查询（/api/agent/session/{id}）
│   ├── MemoryController.java       # 会话长期记忆查询（/api/agent/memory/{id}）
│   ├── DocumentController.java     # 文档知识库上传 / 删除
│   └── McpController.java          # MCP 服务器管理（/api/agent/mcp，随模块开关加载）
├── service/                        # 业务核心
│   ├── AgentService.java           # 协调 LLM、工具、记忆、压缩
│   ├── AgentStreamService.java     # 流式对话编排（SSE）
│   ├── DocumentService.java        # 文档入库：解析 → 切块 → 向量化
│   └── stream/                     # 流式对话底层组件（SSE 客户端/工具执行/事件推送）
├── common/
│   ├── Constant.java               # 常量统一管理
│   └── SystemPrompt.java           # 全部提示词（对话/压缩/视觉/工具说明）
├── config/
│   └── AppConfig.java              # 全局 Bean 装配中心（STM/LTM/会话/rag 缓存/附件/工具禁用/技能）
├── context/                        # 上下文管理
│   ├── ContextManager.java         # 上下文组装 + 溢出压缩 + restoreContextIfEmpty
│   ├── CompressionService.java     # 对话历史压缩（增量）
│   └── SessionContextHolder.java   # ThreadLocal 会话上下文（sessionId + 本次可用技能名单）
├── memory/                         # 记忆模块
│   ├── MessageCodec.java           # 对话消息与 AgentMessage 互转
│   ├── MemoryStore.java            # 长期记忆存储抽象接口
│   ├── VectorMemoryStore.java      # 向量记忆存储封装
│   ├── LongTermMemoryService.java  # 长期记忆业务
│   ├── model/AgentMessage.java     # 统一消息模型
│   └── external/                   # Redis + Milvus 实现
├── session/                        # 会话状态管理
│   ├── SessionManager.java         # 会话状态抽象接口
│   ├── AbstractSessionManager.java # 公共业务逻辑（token/轮次/模型合并）
│   ├── model/SessionMemory.java
│   ├── local/LocalSessionManager.java   # 本地内存实现（默认）
│   └── external/RedisSessionManager.java # Redis 实现（30 分钟 TTL）
├── tool/                           # LLM 可调用工具
│   ├── AgentTool.java              # 工具抽象接口（含默认 id()：类名首字母小写，跨重启稳定）
│   ├── ToolRegistry.java           # 工具注册表（按谓词过滤工具集；按 ID 禁用/启用；MCP 工具并入管理面）
│   ├── ToolResolver.java           # 会话级统一工具解析器（内置/技能 + MCP 合并为 List<ToolCallback>）
│   ├── ToolDisabledStore.java      # 工具禁用状态存储抽象接口
│   ├── local/LocalToolDisabledStore.java   # 禁用状态内存实现（默认）
│   ├── external/RedisToolDisabledStore.java # 禁用状态 Redis 实现（无 TTL）
│   ├── datetime/DateTimeTool.java           # 日期时间
│   ├── memory/SearchMemoryTool.java         # 会话记忆检索（search_memory）
│   ├── document/SearchDocumentTool.java     # 文档知识库检索（search_document）
│   ├── attachment/                          # 附件工具
│   │   ├── ReadFileTool.java                # 附件读取（read_file）
│   │   └── GrepFileTool.java                # 附件检索（grep_file）
│   └── skill/                               # 技能工具（随技能模块加载）
│       ├── SkillTool.java                   # 技能工具标记接口
│       ├── LoadSkillTool.java               # load_skill
│       ├── ExecSkillTool.java               # exec_skill
│       └── ReadSkillFileTool.java           # read_skill_file
├── mcp/                            # MCP（Model Context Protocol）Client 模块（@ConditionalOnProperty(app.mcp.enabled) 门控）
│   ├── McpServerService.java       # 注册/删除/列表/会话记录/工具回调解析/系统提示片段
│   ├── McpConnectionManager.java   # 懒连接 + 每服务器锁 + 工具缓存 TTL 刷新 + 关闭
│   ├── McpToolCallback.java        # 前缀化工具适配器（serverId_tool，call 委托）
│   ├── McpServerProperties.java    # 配置绑定（app.mcp.*）
│   ├── model/McpServerConfig.java  # 服务器定义（transport/command/args/env/url/enabled/global）
│   ├── store/McpServerStore.java   # 服务器存储抽象（定义 CRUD + 会话记录）
│   ├── local/LocalMcpServerStore.java  # 本地内存实现（默认，记录无 TTL）
│   └── external/RedisMcpServerStore.java # Redis 实现（记录无 TTL，重启不丢）
├── skill/                          # 技能（Skills）模块（@ConditionalOnProperty(app.skill.enabled) 门控）
│   ├── SkillService.java           # 注册校验/静态预注册/记录鉴权/解释器判定/编排
│   ├── SkillProperties.java        # 配置绑定（app.skill.*）
│   ├── SkillExecutor.java / LocalSkillExecutor.java  # 脚本执行器（本地进程）
│   ├── model/SkillDefinition.java  # 技能定义
│   ├── store/SkillStore.java       # 技能存储抽象（定义 CRUD + 会话技能记录）
│   ├── local/LocalSkillStore.java  # 本地内存实现（默认，记录无 TTL）
│   └── external/RedisSkillStore.java # Redis 实现（记录无 TTL，重启不丢）
├── attachment/                     # 附件直传模块
│   ├── AttachmentEntry.java        # 附件条目（fileId/归属/路径/过期）
│   ├── AttachmentRegistry.java     # 附件注册表抽象接口
│   ├── AttachmentStore.java        # 落盘/懒解析/删除/清理
│   ├── AttachmentCleanupTask.java  # 定时清理任务（@Scheduled）
│   ├── local/LocalAttachmentRegistry.java   # 注册表本地内存实现（默认）
│   ├── external/RedisAttachmentRegistry.java # 注册表 Redis 实现（TTL，重启不丢）
│   └── resolver/                   # 附件来源解析策略（base64/url/multipart）
│       ├── AttachmentResolver.java          # 解析策略接口
│       ├── AttachmentResolverFactory.java   # 解析策略工厂
│       ├── AttachmentInput.java             # 附件来源输入
│       ├── ResolvedAttachment.java          # 统一字节形态
│       ├── Base64AttachmentResolver.java
│       ├── UrlAttachmentResolver.java
│       └── MultipartAttachmentResolver.java
├── rag/                           # 文档处理核心
│   ├── DocumentParseCache.java    # 解析缓存抽象接口（文件哈希→文本）
│   ├── local/LocalDocumentParseCache.java # 解析缓存本地内存实现（默认）
│   ├── external/RedisDocumentParseCache.java # 解析缓存 Redis 实现（TTL）
│   ├── SemanticTextSplitter.java  # 语义切块器
│   ├── DocumentSplitterFactory.java # 切块策略工厂
│   └── parser/                    # 文件解析策略
│       ├── DocumentParser.java    # 解析策略接口
│       ├── DocumentParserFactory.java # 解析策略工厂
│       ├── TextDocumentParser.java   # 纯文本（txt/md/json）
│       ├── PdfDocumentParser.java    # PDF（文本层 + 图片页视觉描述）
│       ├── WordDocumentParser.java   # Word（docx，Apache POI）
│       ├── SpreadsheetDocumentParser.java # 表格（xlsx/xls/csv，POI 流式 + 手写 CSV）
│       ├── ImageDocumentParser.java  # 图片（png/jpg/jpeg/gif/webp/bmp）
│       └── VisionDescribeService.java # 图片视觉描述（dashscope qwen-vl）
├── dto/                            # 请求/响应结构
│   ├── ChatRequest.java            # question + sessionId + skillIds + mcpServerIds + attachments
│   ├── ChatResponse.java
│   ├── ErrorResponse.java
│   └── DocumentIngestResult.java   # 文档入库结果
└── exception/GlobalExceptionHandler.java  # 全局异常处理（400/404/500 统一错误结构）
```

## 开发约定

- 源码含中文注释，文件统一 UTF-8（无 BOM）
- 不使用 Lombok，模型类手写 getter/setter（开源项目避免额外注解处理依赖）
- 新增存储实现遵循「本地实现放 `local`、外部实现放 `external`、共享抽象（接口/配置/条件）留在根包」的布局

## License

MIT