# graphQLMovieDB-agents

The Java companion for the graphQLGuy course "GraphQL for AI Agents (Beyond MCP)":
agents built with Spring AI and LangChain4j against the Movie Database GraphQL
service from the Spring GraphQL tutorial (`graphQLMovieDB`, expected running on
port 8081).

One branch per class, `agents_class_N`, each holding the cumulative working state
at the end of that class. The Class 3 schema-navigation instrument lives in its own
repository, `graphQLSchemaNav`.

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
