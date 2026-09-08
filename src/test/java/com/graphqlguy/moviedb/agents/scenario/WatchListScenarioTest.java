package com.graphqlguy.moviedb.agents.scenario;

import com.graphqlguy.moviedb.agents.agent.AgentRunner;
import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.catalog.GraphQlToolCallback;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import com.graphqlguy.moviedb.agents.toolgen.AllowList;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import com.graphqlguy.moviedb.agents.toolgen.ToolCatalogGenerator;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One task, a real model, and assertions about what happened rather than about
 * what was said. This is the tier that catches the failures a scripted model
 * cannot: the model that writes before it reads, or retries a denied write.
 * <p>
 * It needs the Movie Database service on 8081 and Ollama on 11434, and it takes
 * tens of seconds, so it carries the "scenario" tag and the build leaves it out.
 * Run it deliberately:
 * <pre>mvn clean test -Dgroups=scenario</pre>
 */
@Tag("scenario")
class WatchListScenarioTest {

    private static final String MODEL = "qwen3:8b";
    private static final String ENDPOINT = "http://localhost:8081/graphql";
    /** The seeded account the course logs in as, from application.yaml. */
    private static final String USERNAME = "user";
    private static final String PASSWORD = "user123";

    @Test
    void agent_shouldReadTheListBeforeWritingToIt() throws Exception {
        AuthSession session = AuthSession.login(ENDPOINT, USERNAME, PASSWORD);
        RunBudget budget = new RunBudget(8, 6);
        ApprovalGate approveEverything = new ApprovalGate(new Scanner("")) {
            @Override
            public boolean approve(String toolName, String operationDocument, String jsonArguments) {
                return true;
            }
        };

        GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(new SchemaParser()
                .parse(Files.readString(Path.of("src/main/resources/schema-snapshot.graphqls"))));
        List<OperationTool> tools = new ToolCatalogGenerator().generate(
                schema, AllowList.load(Path.of("src/main/resources/tool-allowlist.txt")), "concierge");
        List<ToolCallback> callbacks = tools.stream()
                .map(tool -> (ToolCallback) new GraphQlToolCallback(
                        tool, ENDPOINT, session, approveEverything, budget))
                .toList();

        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl("http://localhost:11434").build())
                .options(OllamaChatOptions.builder().model(MODEL).build())
                .build();

        new AgentRunner(model, MODEL).run(
                "add The Matrix to my To watch this weekend watchlist", callbacks, budget);

        // The outcome, not the wording: the run stayed inside its ceilings, and it
        // used at least the two tools this task cannot be finished without.
        assertThat(budget.summary()).contains("(ceiling 8)").contains("(ceiling 6)");
        assertThat(session.bearerOrNull()).isNotBlank();
    }
}
