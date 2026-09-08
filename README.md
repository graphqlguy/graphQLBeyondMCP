# graphQLBeyondMCP - Class 4

The Java companion for our [GraphQL for AI Agents (Beyond MCP) course](https://graphqlguy.com/docs/tutorial-ai-agents/overview): an agent built with Spring AI, LangChain4j and LangGraph4j against the Movie Database GraphQL service. That service is a separate repository, [`graphQLMovieDB-agents`](https://github.com/graphqlguy/graphQLMovieDB-agents), and the course expects it running on port 8081.

This branch is the repository state at the end of [Class 4: Safety: Approval Gates, Budgets, and the Server's Last Word](https://graphqlguy.com/docs/tutorial-ai-agents/safety-and-allow-lists).

**What Class 4 adds:** `ApprovalGate` and `RunBudget`, the `agent` command that runs the whole loop behind them, `ProbeDepthCommand` for the server's own depth cap, and the first hand-written tool selections.

**Following along:** start from `agents_class_2`, work through the lesson, then compare your result with this branch. The next class with code is `agents_class_5`.

One branch per class, `agents_class_N`, each holding the cumulative state at the end of that class:

| Branch | Class |
| --- | --- |
| `agents_class_2` | [Class 2: Auto-Generating Tool Definitions from a Schema](https://graphqlguy.com/docs/tutorial-ai-agents/auto-generating-tool-definitions) |
| `agents_class_4` | [Class 4: Safety: Approval Gates, Budgets, and the Server's Last Word](https://graphqlguy.com/docs/tutorial-ai-agents/safety-and-allow-lists) |
| `agents_class_5` | [Class 5: LangChain4j Integration](https://graphqlguy.com/docs/tutorial-ai-agents/langchain4j-integration) |
| `agents_class_6` | [Class 6: LangGraph4j Integration](https://graphqlguy.com/docs/tutorial-ai-agents/langgraph-integration) |
| `agents_class_7` | [Class 7: Spring AI Integration](https://graphqlguy.com/docs/tutorial-ai-agents/spring-ai-integration) |
| `agents_class_8` | [Class 8: Structured Output via Fragments](https://graphqlguy.com/docs/tutorial-ai-agents/structured-output-via-fragments) |
| `agents_class_9` | [Class 9: Streaming and Incremental Delivery on the JVM](https://graphqlguy.com/docs/tutorial-ai-agents/defer-and-tool-streaming) |
| `agents_class_10` | [Class 10: Cost Accounting per Agent Run](https://graphqlguy.com/docs/tutorial-ai-agents/cost-accounting-per-agent-run) |
| `agents_class_11` | [Class 11: Testing Agent-Driven Queries](https://graphqlguy.com/docs/tutorial-ai-agents/testing-agent-driven-queries) |

Classes 1 and 12 add no code. Class 3's schema-navigation instrument lives in its own repository, [`graphQLSchemaNav`](https://github.com/graphqlguy/graphQLSchemaNav).

## Class 2: tool generation

```bash
# Generate and print the tool catalog (the whole one, or one role's view)
mvn -q spring-boot:run -Dspring-boot.run.arguments=catalog
mvn -q spring-boot:run -Dspring-boot.run.arguments="catalog support"

# Execute one generated tool against the running service, no model involved
mvn -q package -DskipTests
java -jar target/moviedb-agents-0.0.1-SNAPSHOT.jar call movie '{"id":"1"}'
```

The generator walks the schema snapshot, keeps only allow-listed operations
(`src/main/resources/tool-allowlist.txt`, plain text, reviewed like code), maps
argument types to JSON Schema, emits one persisted operation document per tool,
and reports what advertising the catalog costs in tokens. The `call` command runs
a tool through the same Spring AI `ToolCallback` contract a chat model would use.

## Class 4: safety

```bash
# The full agent loop: real login, role-scoped tools, approval-gated writes,
# and hard run budgets (any tool-calling model Ollama serves)
mvn -q package -DskipTests
java -jar target/moviedb-agents-0.0.1-SNAPSHOT.jar agent add the movie with id 5 to my To watch this weekend watchlist

# The server's own depth cap refusing a pathological 20-level query:
# the backstop that holds even if every agent-side control fails
java -jar target/moviedb-agents-0.0.1-SNAPSHOT.jar probe-depth
```

The safety controls live at the tool layer, where execution happens: the run
budget counts every call, mutations pause for console approval, and the
Authorization header carries a real login (the seeded admin account), so the
server's own authorization always has the last word. Curated selection
overrides under `src/main/resources/tool-selections/` replace generated
documents where a human decided differently; the two overrides present work
around a real DateTime serialization defect in the service that the agent's
first run discovered.
