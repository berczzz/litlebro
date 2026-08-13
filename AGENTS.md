# AGENTS.md

面向 AI 编码代理（及新开发者）的项目初始化指南。

## 项目概述

`litlebro`（Spring AI Agent Demo）是一个基于 Spring Boot 3.4 + Spring AI 1.0.0-M6 的
AI Agent 演示项目。它通过 ChatClient 调用 OpenAI 兼容的 LLM，并实现了一套
**二层记忆架构**（短期/长期记忆）+ compaction 压缩机制（借鉴 opencode）和可插拔的工具调用机制。

- **语言/构建**：Java 17，Maven
- **主启动类**：`com.litlebro.agent.LitlebroApplication`
- **端口**：8080（可在 `application.yml` 中修改）

## 常用命令

> 注意：本机 Maven 默认指向 JDK 8，编译前必须切换到 JDK 17，否则报
> `无效的标记: --release`。

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
├── controller/AgentController.java  # REST 入口
├── service/AgentService.java      # 业务核心：协调 LLM、工具、记忆、压缩
├── common/SystemPrompt.java       # 系统提示词 + 压缩提示词
├── context/                       # 上下文管理
│   ├── ContextManager.java        # 上下文组装 + 溢出压缩（compactIfNeeded）
│   └── CompressionService.java    # 对话历史压缩（支持增量压缩）
├── memory/                        # 记忆模块
│   ├── MemoryConfig.java          # 装配中心（启用 @Async，STM/LTM 独立配置）
│   ├── MemoryStore.java           # 长期记忆存储抽象接口
│   ├── VectorMemoryStore.java     # 长期记忆向量存储封装
│   ├── LongTermMemoryService.java # 长期记忆业务服务（摘要存取 + 上下文构建）
│   ├── model/Memory.java          # 长期记忆实体（按 sessionId 隔离）
│   ├── inmemory/                  # 内存记忆实现（RedisChatMemory 之外的实现可放此包）
│   └── external/
│       └── RedisChatMemory.java               # 短期记忆（30 分钟 TTL）
├── tool/                          # LLM 可调用工具
│   ├── AgentTool.java
│   ├── DateTimeTool.java
│   └── ToolRegistry.java
├── dto/                           # 请求/响应结构
│   ├── ChatRequest.java           # question + sessionId（无 userId）
│   ├── ChatResponse.java
│   └── ErrorResponse.java
└── exception/GlobalExceptionHandler.java  # 全局异常处理
```

## 记忆架构

二层记忆由两个独立配置项分别切换，互不影响：

| 记忆层 | 配置项 | 内存实现 | 外部实现 |
| --- | --- | --- | --- |
| 短期（ChatMemory） | `app.memory.stm.type` | `InMemoryChatMemory`（`inmemory`） | `RedisChatMemory`（`redis`，30 分钟 TTL） |
| 长期（VectorStore） | `app.memory.ltm.type` | `SimpleVectorStore`（`inmemory`） | `MilvusVectorStore`（`milvus`） |

**记忆按 sessionId 隔离**，每个会话有独立的记忆空间。

- STM 用 `redis` 时会话状态（SessionManager）也跟随存 Redis，否则回退内存
- 存储实现互不耦合，可自由组合（如 STM 用 redis + LTM 用 inmemory）

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

## 配置说明

关键配置在 `src/main/resources/application.yml`，均支持环境变量覆盖：

| 配置 | 环境变量 | 默认值 |
| --- | --- | --- |
| LLM API Key | `OPENAI_API_KEY` | `your-api-key-here` |
| LLM Base URL | `OPENAI_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode` |
| 模型 | — | `qwen3.8-max` |
| Redis 地址 | `REDIS_HOST` / `REDIS_PORT` | `localhost:6379` |
| Milvus 地址 | `MILVUS_HOST` / `MILVUS_PORT` | `localhost:19530` |
| 短期记忆类型 | `APP_MEMORY_STM_TYPE`（`app.memory.stm.type`） | `inmemory` |
| 长期记忆类型 | `APP_MEMORY_LTM_TYPE`（`app.memory.ltm.type`） | `inmemory` |

## 开发约定

- **编码**：源码含中文注释，文件统一使用 UTF-8（无 BOM）。
- **注释语言**：javadoc 与行内注释均用中文书写。
- **内存实现位于 `memory.inmemory`，外部实现位于 `memory.external`，
  共享抽象（接口/配置/条件）留在 `memory` 根包**；新增存储实现时遵循此布局。
- 新增功能建议优先遵循现有二层记忆 + 工具回调 + compaction 的模式。
- 切勿将 API Key 等机密写入仓库或提交。