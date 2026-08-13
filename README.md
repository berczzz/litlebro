# litlebro — 小老弟

一个基于 **Spring Boot 3.4 + Spring AI 1.0.0-M6** 的 AI Agent 演示项目。
通过 ChatClient 调用 OpenAI 兼容的 LLM，
实现了一套**二层记忆架构**（短期/长期记忆）+ **compaction 压缩机制**
和**可插拔的工具调用机制**。

## 特性

- **对话式 Agent**：REST API 调用，支持多会话隔离（sessionId）
- **二层记忆架构**：
  - 短期记忆（ChatMemory）— 维护最近对话历史
  - 长期记忆（向量库）— 持久化消息与压缩摘要
- **Compaction 压缩机制**：
  - LLM 调用后置检查 token 占用，超阈值（模型窗口 75%）自动触发增量压缩
  - 保留最近 6 条消息原文，更早的历史压缩为摘要存入长期记忆
  - 压缩时传入旧摘要做增量，不重复压缩同一段历史
- **可插拔工具**：日期时间等，LLM 按需自动调用
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

## 快速开始

### 1. 环境要求

- JDK 17
- Maven 3.6+
- （可选）Redis、Milvus

### 2. 配置

编辑 `src/main/resources/application.yml`，设置 LLM 相关参数
（均支持环境变量覆盖）：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| API Key | `OPENAI_API_KEY` | 需自行填写 |
| Base URL | `OPENAI_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode` |
| 模型 | — | `qwen3.8-max` |

> 本项目默认对接阿里云 DashScope 的 OpenAI 兼容模式，也可改为任意兼容网关。
> 注意：Spring AI 会自动拼接 `/v1/{endpoint}`，base-url **不要带尾部 `/v1`**。

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
  -d '{"question": "今天几号？", "sessionId": "demo-1"}'

# 工具列表
curl http://localhost:8080/api/agent/tools

# 会话统计（token、轮次）
curl http://localhost:8080/api/agent/session/demo-1

# 长期记忆（摘要）
curl http://localhost:8080/api/agent/memory/demo-1
```

## API 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agent/chat` | 对话，body: `{"question":"...", "sessionId":"..."}` |
| GET | `/api/agent/tools` | 可用工具列表 |
| GET | `/api/agent/session/{sessionId}` | 会话统计（token 累积、轮次、模型） |
| GET | `/api/agent/memory/{sessionId}` | 会话长期记忆（摘要） |

## 记忆架构

记忆按 `app.memory.mode` 切换，两个子包互斥生效：

| 记忆层 | 内存实现 | 外部实现 |
| --- | --- | --- |
| 短期（ChatMemory） | `InMemoryChatMemory` | `RedisChatMemory`（30 分钟 TTL） |
| 长期（VectorStore） | `SimpleVectorStore` | `MilvusVectorStore` |
| 会话状态（SessionManager） | ConcurrentHashMap | Redis（30 分钟 TTL） |

**记忆按 sessionId 隔离**，每个会话有独立记忆空间。

模式判断见 `MemoryModeConditions`：

- `auto`（默认）：`REDIS_ENABLED` 与 `MILVUS_ENABLED` 均为 `true` 时用外部实现，否则回退内存
- `memory`：强制内存实现
- `external`：强制 Redis + Milvus

### 配置说明

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| 记忆模式 | `APP_MEMORY_MODE` | `auto` |
| Redis 地址 | `REDIS_HOST` / `REDIS_PORT` | `localhost:6379` |
| Milvus 地址 | `MILVUS_HOST` / `MILVUS_PORT` | `localhost:19530` |
| Redis 启用 | `REDIS_ENABLED` | `false` |
| Milvus 启用 | `MILVUS_ENABLED` | `false` |

### Compaction 压缩流程

每次对话后检查会话累积的输入 token 是否超出模型窗口的 75%，超出则触发增量压缩：

1. 保留最近 **6 条消息**原文
2. 更早的旧消息 + 上一次压缩摘要 → LLM 压缩 → 新摘要（`CATEGORY_SUMMARY`）
3. 新摘要存入长期记忆，并作为 SystemMessage 重新注入短期记忆
4. 重置当前 token 占用，继续后续对话
5. 下次压缩时传入旧摘要做增量，不重复压缩同一段历史

压缩提示词是任务导向的（"做了什么、决定了什么、什么还没做"），而非提取个人事实。

## 工具调用

内置工具（`com.litlebro.agent.tool`），LLM 会根据问题自动选择：

- `DateTimeTool` — 获取当前日期时间

新增工具只需实现 `AgentTool` 接口并在 `ToolRegistry` 注册。

## 项目结构

```
src/main/java/com/litlebro/agent/
├── LitlebroApplication.java        # 主启动类
├── controller/AgentController.java  # REST 入口
├── service/AgentService.java      # 业务核心：协调 LLM、工具、记忆、压缩
├── common/SystemPrompt.java       # 系统提示词 + 压缩提示词
├── context/                       # 上下文管理
│   ├── ContextManager.java        # 后置溢出检查 + 压缩触发
│   └── CompressionService.java    # 对话历史压缩（增量）
├── memory/                        # 记忆模块
│   ├── MemoryConfig.java          # 装配中心
│   ├── LongTermMemoryService.java # 长期记忆业务
│   ├── inmemory/                  # 内存记忆实现
│   └── external/                  # Redis + Milvus 实现
├── session/                       # 会话状态管理
│   ├── SessionManager.java        # 会话 token/轮次统计（Redis + 内存双模式）
│   └── model/SessionMemory.java
├── tool/                          # LLM 可调用工具
├── dto/                           # 请求/响应结构
└── exception/GlobalExceptionHandler.java
```

## 开发约定

- 源码含中文注释，文件统一 UTF-8（无 BOM）
- 新增存储实现遵循「内存实现放 `memory.inmemory`、外部实现放 `memory.external`、
  共享抽象留在 `memory` 根包」的布局

## License

MIT