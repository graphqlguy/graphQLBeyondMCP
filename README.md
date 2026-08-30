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
