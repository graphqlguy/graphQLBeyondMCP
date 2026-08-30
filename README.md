# graphQLMovieDB-agents

Companion code for the **GraphQL for AI Agents (Beyond MCP)** course: a Java agent
built against the Movie Database GraphQL service (`graphQLMovieDB`, expected running
on port 8081).

## How this repository is organised

`main` is the **starting point**, not a finished project. It carries the project
skeleton, the Maven dependencies, and the committed schema snapshot, and nothing
else. Clone it and follow the classes; each one adds the code it teaches.

Every class that changes code has a branch, `agents_class_N`, holding the cumulative
state at the end of that class:

| Branch | Class |
| --- | --- |
| `agents_class_2` | Auto-generating tool definitions from a schema |
| `agents_class_4` | Safety: approval gates, budgets, and the server's last word |
| `agents_class_5` | LangChain4j integration |
| `agents_class_6` | LangGraph4j integration |
| `agents_class_7` | Spring AI integration |
| `agents_class_8` | Structured output via fragments |
| `agents_class_9` | Streaming and incremental delivery |
| `agents_class_10` | Cost accounting per agent run |
| `agents_class_11` | Testing agent-driven queries |

Classes 1 and 12 are theory and add no code. Class 3's schema-navigation instrument
lives in its own repository, `graphQLSchemaNav`.
