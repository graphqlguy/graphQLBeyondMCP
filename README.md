# graphQLBeyondMCP

The Java companion for our [GraphQL for AI Agents (Beyond MCP) course](https://graphqlguy.com/docs/tutorial-ai-agents/overview): an agent built with Spring AI, LangChain4j and LangGraph4j against the Movie Database GraphQL service. That service is a separate repository, [`graphQLMovieDB-agents`](https://github.com/graphqlguy/graphQLMovieDB-agents), and the course expects it running on port 8081.

**`main` is the course's starting point.** It carries the project skeleton, the Maven dependencies, and the committed schema snapshot, and each class branch adds the code its lesson teaches.

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
