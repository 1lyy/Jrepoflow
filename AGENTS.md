# AGENTS.md

JRepoFlow 仓库的 Agent 协作入口。代码实际行为优先于本文档。

## 项目定位

JRepoFlow 是面向本地 Java 仓库的智能开发 CLI，核心范围只有：

- ReAct 工具执行；
- Plan + DAG 就绪批次并行调度；
- Planner / Worker / Reviewer 多 Agent 协作；
- 会话压缩、增量 Session Memory 与长期记忆；
- JavaParser AST 分块和混合代码检索；
- MCP JSON-RPC 客户端，支持 stdio 与 Streamable HTTP。

不要重新引入与该主线无关的浏览器自动化、TUI、微信通道、LSP、Skill、运行时 API、图片输入或 Side-Git 快照。

## 环境与命令

```bash
cp .env.example .env
mvn clean package
java -jar target/jrepoflow-1.0-SNAPSHOT.jar
mvn test -Pquick
mvn test -DskipTests=false
```

要求 Java 17+ 和 Maven。代码检索可选安装 ripgrep；缺失时自动回退到 Java 扫描。

## 核心入口

| 任务 | 先看 |
|---|---|
| CLI 命令 | `cli/Main.java`、`cli/CliCommandParser.java` |
| ReAct | `agent/Agent.java` |
| Plan + DAG | `agent/PlanExecuteAgent.java`、`plan/ExecutionPlan.java` |
| Multi-Agent | `agent/AgentOrchestrator.java`、`agent/SubAgent.java`、`agent/AgentMessage.java` |
| Memory | `memory/MemoryManager.java`、`memory/AutoCompactionManager.java` |
| RAG | `rag/CodeChunker.java`、`rag/CodeRetriever.java`、`rag/VectorStore.java` |
| MCP | `mcp/McpServerManager.java`、`mcp/McpClient.java`、`mcp/jsonrpc/JsonRpcClient.java` |
| 工具与并发 | `tool/ToolRegistry.java` |

## 行为约束

- 独立工具调用统一通过 `ToolRegistry.executeTools()` 并行执行，结果保持输入顺序。
- Plan 任务只有依赖全部完成后才可进入 ready batch；失败节点不能误标为完成。
- Multi-Agent 由 Orchestrator 集中路由消息；Worker 只执行依赖已完成的任务，Reviewer 最多要求重试两次。
- 长期记忆只能由用户显式保存，并必须支持查询和删除。
- 自动压缩优先使用预计算的增量摘要，失效或收益不足时回退完整摘要。
- 精确代码定位优先 `glob_files`、`grep_code`、`read_file`；`search_code` 只做语义辅助。
- MCP 配置合并 `~/.jrepoflow/mcp.json` 与 `.jrepoflow/mcp.json`，项目级同名配置优先。
- 路径必须限制在项目根目录；不要提交 `.env`、真实密钥、索引数据库或 `target/`。

## 修改联动

- 修改 CLI 命令：同步 `Main.java`、`CliCommandParser.java`、测试和 README。
- 修改工具：同步 `ToolRegistry.java`、Agent/Plan 提示词、测试和 README。
- 修改 Multi-Agent：同步角色提示词、调度与审查测试、CLI 和 README。
- 修改 Memory、RAG 或 MCP：同步对应模块测试与文档。
- 删除能力时同时删除依赖、提示词、测试和过时文档，避免留下不可达代码。

## 验证建议

```bash
mvn test -Dtest=ExecutionPlanTest -DskipTests=false
mvn test -Dtest=CodeChunkerTest,VectorStoreTest,CodeIndexTest -DskipTests=false
mvn test -Dtest=McpClientTest,JsonRpcClientTest,McpServerManagerTest -DskipTests=false
mvn test -Dtest=ToolRegistryTest,CliCommandParserTest -DskipTests=false
```
