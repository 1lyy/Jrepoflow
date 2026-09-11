# JRepoFlow Roadmap

The public repository is intentionally focused. Items below are future improvements, not delivered features.

## Current scope

- ReAct agent with local repository tools
- Plan + DAG ready-batch parallel execution
- Dual-path context compaction and explicit long-term memory
- JavaParser AST chunking with Embedding + keyword retrieval
- MCP JSON-RPC client over stdio and Streamable HTTP
- Project path, command and audit policies

## Next

- Add reproducible RepoBench scripts and checked-in metric summaries without committing the dataset.
- Add weighted hybrid retrieval calibration and query-level error analysis.
- Add DAG retry and resume metadata for interrupted plans.
- Add MCP reconnect/backoff and protocol conformance tests.
- Add a provider-neutral local model configuration based on Ollama.

## Non-goals

Browser automation, chat channels, graphical TUI, multi-agent orchestration and hosted runtime APIs are deliberately outside the current repository scope.
