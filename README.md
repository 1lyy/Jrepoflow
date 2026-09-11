# JRepoFlow

JRepoFlow（Java Repository Workflow）是一个面向本地 Java 代码仓库的智能开发 CLI。它把模型推理、代码检索、工具执行、任务规划和上下文管理组织成一条完整流程，帮助开发者理解、修改和验证项目代码。

项目聚焦于四个核心方向：Plan + DAG、分层 Memory、代码 RAG 和 MCP 工具接入。

## 技术栈

- Java 17、JUC
- OkHttp、Jackson
- SQLite、JavaParser、Jieba
- Ollama Embedding
- MCP、JSON-RPC 2.0

## 核心设计

### Plan + DAG

复杂任务先由 Planner 生成结构化计划，再转换为带依赖关系的任务图。调度器每轮提取所有已就绪任务，通过线程池并行执行互不依赖的节点；后继节点只有在依赖完成后才会进入下一批次。任务结果按原计划顺序归并，兼顾并发效率和输出稳定性。

### 分层 Memory

Memory 模块统一管理当前会话、上下文压缩和 SQLite 长期记忆：

- 会话历史保存当前任务的完整交互上下文。
- 增量 Session Memory 在阈值前异步生成摘要。
- 摘要未就绪或压缩收益不足时，回退到完整会话摘要。
- 长期记忆只在用户明确要求时写入，并支持查询和删除。

双摘要策略使长任务可以持续执行，同时避免上下文窗口被历史消息占满。

### 面向代码库的 RAG

索引阶段使用 JavaParser 解析 AST，按类、接口、枚举和方法切分 Java 源码，同时提取代码关系。检索阶段融合 Embedding 相似度与 Jieba 关键词得分，向 Agent 返回相关代码片段；元数据和向量持久化到 SQLite。

在 RepoBench Java v1.1 数据集的项目内评测中，混合检索 Recall@5 相较 BM25 基线提高 9.7 个百分点。仓库中的 [deterministic golden set](docs/code-search-golden-set.md) 另用于保障精确代码搜索链路的正确性。

### MCP 客户端

MCP 客户端基于 JSON-RPC 2.0 实现，支持：

- stdio 子进程传输；
- Streamable HTTP + SSE 传输；
- initialize 握手和工具自动发现；
- 将外部工具动态注册为 `mcp__{server}__{tool}`；
- 请求 ID 与异步响应关联、超时和错误处理；
- 用户级与项目级配置合并。

## 执行流程

```text
用户目标
   │
   ├─ 普通任务 ──> ReAct Agent ──> ToolRegistry ──> 文件 / 命令 / RAG / MCP
   │
   └─ /plan ─────> Planner ──> DAG 就绪批次 ──> 并行执行 ──> 结果汇总
                                      │
                         Memory <─────┴─────> Context Compaction
```

## 快速开始

环境要求：JDK 17+、Maven 3.8+。使用本地 Embedding 时还需要 Ollama。

```bash
git clone <your-repository-url>
cd jrepoflow
cp .env.example .env
mvn clean package
java -jar target/jrepoflow-1.0-SNAPSHOT.jar
```

在 `.env` 中至少配置一个模型服务的 API Key，例如：

```dotenv
GLM_API_KEY=your_api_key
GLM_MODEL=glm-4.5

EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434
```

默认构建跳过测试。运行回归测试：

```bash
mvn test -Pquick
mvn test -DskipTests=false
```

## 常用命令

| 命令 | 作用 |
|---|---|
| `/plan <任务>` | 生成 DAG，并按依赖批次执行 |
| `/index [目录]` | 建立 AST + Embedding 代码索引 |
| `/search <查询>` | 执行混合代码检索 |
| `/graph <名称>` | 查询类或方法关系 |
| `/save <内容>` | 保存项目级长期记忆 |
| `/memory` | 查看 Memory 状态 |
| `/compact` | 手动压缩当前会话 |
| `/context` | 查看上下文预算 |
| `/mcp` | 查看 MCP 服务状态 |
| `/audit [条数]` | 查看最近工具审计记录 |
| `/clear` | 清空当前会话，保留长期记忆 |

## MCP 配置

JRepoFlow 会合并以下配置，项目级同名服务覆盖用户级配置：

- 用户级：`~/.jrepoflow/mcp.json`
- 项目级：`.jrepoflow/mcp.json`

stdio 与 Streamable HTTP 示例：

```json
{
  "mcpServers": {
    "local-tools": {
      "command": "java",
      "args": ["-jar", "${PROJECT_DIR}/tools/local-mcp.jar"],
      "env": {
        "WORKSPACE": "${PROJECT_DIR}"
      }
    },
    "remote-tools": {
      "url": "https://example.com/mcp",
      "headers": {
        "Authorization": "Bearer ${REMOTE_TOKEN}"
      }
    }
  }
}
```

配置支持 `${PROJECT_DIR}`、`${HOME}` 和环境变量占位符。

## 项目结构

```text
src/main/java/io/jrepoflow/
├── agent/      ReAct 与 Plan-and-Execute 主流程
├── plan/       Planner、Task、ExecutionPlan DAG
├── memory/     会话压缩、Session Memory、长期记忆
├── rag/        AST 分块、Embedding、混合检索、SQLite 向量存储
├── mcp/        MCP 客户端、JSON-RPC、stdio/HTTP 传输
├── tool/       内置工具注册与并行执行
├── llm/        模型客户端与重试策略
├── policy/     路径、命令和审计策略
├── prompt/     分层提示词组装
└── cli/        命令解析与程序入口
```

## 安全边界

- 文件操作限制在当前项目根目录。
- 危险 Shell 命令由策略层拒绝。
- 工具调用写入本地审计日志。
- `.env`、API Key、`target/` 和本地索引不会提交到仓库。
