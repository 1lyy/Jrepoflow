package io.jrepoflow.cli;

import io.jrepoflow.agent.Agent;
import io.jrepoflow.agent.PlanExecuteAgent;
import io.jrepoflow.config.JRepoFlowConfig;
import io.jrepoflow.llm.LlmClient;
import io.jrepoflow.llm.LlmClientFactory;
import io.jrepoflow.mcp.McpServerManager;
import io.jrepoflow.memory.MemoryEntry;
import io.jrepoflow.rag.CodeIndex;
import io.jrepoflow.rag.CodeRelation;
import io.jrepoflow.rag.CodeRetriever;
import io.jrepoflow.rag.SearchResultFormatter;
import io.jrepoflow.tool.ToolRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * JRepoFlow 的终端入口。
 *
 * <p>入口只负责组装核心能力和分派命令，Agent 推理、DAG 调度、Memory、RAG 与
 * MCP 都保留在各自模块中，避免把业务逻辑堆进 CLI。</p>
 */
public final class Main {
    private static final String VERSION = "1.0.0";

    private Main() {
    }

    public static void main(String[] args) {
        JRepoFlowConfig config = JRepoFlowConfig.load();
        LlmClient llmClient = LlmClientFactory.createFromConfig(config);
        if (llmClient == null) {
            System.err.println("未找到可用的模型配置，请复制 .env.example 为 .env 并填写 API Key。");
            return;
        }

        Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setProjectPath(projectRoot.toString());

        try (McpServerManager mcpManager = new McpServerManager(toolRegistry, projectRoot)) {
            startMcp(mcpManager);
            runShell(llmClient, toolRegistry, mcpManager, projectRoot);
        } catch (Exception e) {
            System.err.println("JRepoFlow 启动失败: " + e.getMessage());
        }
    }

    private static void startMcp(McpServerManager manager) {
        try {
            manager.loadConfiguredServers();
            manager.startAll(System.out, Duration.ofSeconds(8));
        } catch (IOException e) {
            System.err.println("MCP 配置加载失败，核心 CLI 仍可使用: " + e.getMessage());
        }
    }

    private static void runShell(LlmClient llmClient, ToolRegistry tools,
                                 McpServerManager mcpManager, Path projectRoot) throws IOException {
        Agent agent = new Agent(llmClient, tools);
        System.out.printf("JRepoFlow v%s | %s/%s | %s%n",
                VERSION, llmClient.getProviderName(), llmClient.getModelName(), projectRoot);
        printHelp();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("\n> ");
            System.out.flush();
            String input = reader.readLine();
            if (input == null) {
                break;
            }
            CliCommandParser.ParsedCommand command = CliCommandParser.parse(input);
            if (command.type() == CliCommandParser.CommandType.NONE) {
                if (!input.isBlank()) {
                    printAgentResult(agent.run(input));
                }
                continue;
            }
            if (command.type() == CliCommandParser.CommandType.EXIT) {
                break;
            }
            handleCommand(command, agent, llmClient, tools, mcpManager, projectRoot);
        }
    }

    private static void handleCommand(CliCommandParser.ParsedCommand command, Agent agent,
                                      LlmClient llmClient, ToolRegistry tools,
                                      McpServerManager mcpManager, Path projectRoot) {
        String payload = command.payload();
        try {
            switch (command.type()) {
                case HELP -> printHelp();
                case CLEAR -> {
                    agent.clearHistory();
                    System.out.println("会话上下文已清空，长期记忆保留。");
                }
                case COMPACT -> printCompaction(agent.compactHistoryNow());
                case CONTEXT_STATUS -> System.out.println(agent.getContextStatus());
                case MEMORY_STATUS -> System.out.println(agent.getMemoryManager().getSystemStatus());
                case MEMORY_LIST -> printMemories(agent.getMemoryManager().listLongTerm());
                case MEMORY_SEARCH -> printMemories(agent.getMemoryManager().searchLongTerm(payload, 20));
                case MEMORY_DELETE -> System.out.println(agent.getMemoryManager().deleteLongTerm(payload)
                        ? "记忆已删除。" : "未找到该记忆。");
                case MEMORY_CLEAR -> {
                    agent.getMemoryManager().clearLongTerm();
                    System.out.println("长期记忆已清空。");
                }
                case MEMORY_SAVE -> {
                    requirePayload(payload, "/save <内容>");
                    agent.getMemoryManager().storeFact(payload);
                    System.out.println("已保存到项目级长期记忆。");
                }
                case PLAN -> {
                    requirePayload(payload, "/plan <任务>");
                    PlanExecuteAgent planAgent = new PlanExecuteAgent(
                            llmClient, tools, agent.getMemoryManager(),
                            (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), System.out);
                    printAgentResult(planAgent.run(payload));
                }
                case INDEX -> {
                    Path target = payload == null || payload.isBlank()
                            ? projectRoot : projectRoot.resolve(payload).normalize();
                    new CodeIndex(System.out::println).index(target.toString());
                }
                case SEARCH -> {
                    requirePayload(payload, "/search <查询>");
                    try (CodeRetriever retriever = new CodeRetriever(projectRoot.toString())) {
                        System.out.println(SearchResultFormatter.formatForCli(
                                payload, retriever.hybridSearch(payload, 5)));
                    }
                }
                case GRAPH -> {
                    requirePayload(payload, "/graph <类名或方法名>");
                    try (CodeRetriever retriever = new CodeRetriever(projectRoot.toString())) {
                        printRelations(retriever.getRelationGraph(payload));
                    }
                }
                case MCP_LIST -> System.out.println(mcpManager.formatStatus());
                case MCP_RESTART -> System.out.println(mcpManager.restart(payload));
                case MCP_LOGS -> System.out.println(mcpManager.logs(payload));
                case MCP_DISABLE -> System.out.println(mcpManager.disable(payload));
                case MCP_ENABLE -> System.out.println(mcpManager.enable(payload));
                case POLICY_STATUS -> System.out.println(
                        "文件访问限定在项目根目录；危险命令由策略层拒绝；MCP 调用写入审计日志。");
                case AUDIT_TAIL -> tools.getAuditLog().readRecent(parsePositiveInt(payload, 20))
                        .forEach(System.out::println);
                case UNKNOWN_COMMAND -> System.out.println("未知命令。输入 /help 查看可用命令。");
                default -> {
                }
            }
        } catch (Exception e) {
            System.err.println("命令执行失败: " + e.getMessage());
        }
    }

    private static void printMemories(List<MemoryEntry> entries) {
        if (entries.isEmpty()) {
            System.out.println("暂无匹配的长期记忆。");
            return;
        }
        entries.forEach(entry -> System.out.printf("- %s | %s%n", entry.getId(), entry.getContent()));
    }

    private static void printRelations(List<CodeRelation> relations) {
        if (relations.isEmpty()) {
            System.out.println("未找到代码关系，请先执行 /index。");
            return;
        }
        relations.forEach(relation -> System.out.println("- " + relation));
    }

    private static void printCompaction(Agent.CompactionResult result) {
        if (result.error() != null) {
            System.out.println("压缩失败: " + result.error());
        } else if (!result.compacted()) {
            System.out.println("当前上下文无需压缩。");
        } else {
            System.out.printf("上下文已压缩：%d -> %d tokens%n",
                    result.beforeTokens(), result.afterTokens());
        }
    }

    private static void printAgentResult(String result) {
        if (result != null && !result.isBlank()) {
            System.out.println(result);
        }
    }

    private static void requirePayload(String payload, String usage) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("用法: " + usage);
        }
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            return value == null ? fallback : Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void printHelp() {
        System.out.println("""
                核心命令:
                  /plan <任务>       生成 DAG 并按依赖批次执行
                  /index [目录]      建立 AST + Embedding 代码索引
                  /search <查询>     混合检索代码
                  /graph <名称>      查看类或方法关系
                  /memory            查看 Memory 状态
                  /memory list       列出长期记忆
                  /memory search <词>
                  /memory delete <id>
                  /memory clear
                  /save <内容>       保存项目级长期记忆
                  /compact           手动压缩会话上下文
                  /context           查看上下文预算
                  /mcp               查看 MCP 服务
                  /mcp restart|logs|disable|enable <name>
                  /policy | /audit [条数]
                  /clear | /help | /exit
                """);
    }
}
