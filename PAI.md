# JRepoFlow Project Context

## Positioning

Local-first Java repository agent focused on Plan + DAG, Memory, code RAG and MCP tool integration.

## Commands

- Build: `mvn clean package`
- Run: `java -jar target/jrepoflow-1.0-SNAPSHOT.jar`
- Quick tests: `mvn test -Pquick`
- Full tests: `mvn test -DskipTests=false`

## Architecture

- `Agent` runs the default ReAct loop.
- `PlanExecuteAgent` turns a planner response into an `ExecutionPlan` and executes ready DAG batches.
- `MemoryManager` owns conversation compaction and explicit long-term memory.
- `CodeIndex` and `CodeRetriever` implement AST-based indexing and hybrid retrieval.
- `McpServerManager` discovers MCP tools and registers them in `ToolRegistry`.

## Guardrails

- Keep file access inside the project root.
- Do not add features outside the focused public scope without an explicit request.
- Never commit `.env`, credentials, `target/`, local databases or logs.
- Keep comments focused on design decisions and invariants, not obvious syntax.
