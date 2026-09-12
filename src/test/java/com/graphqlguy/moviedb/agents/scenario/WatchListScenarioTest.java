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
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One task, a real model, and assertions about what the agent did. The wording of
 * its answer is ignored. This is the tier that catches the failures a scripted model
 * cannot: the model that writes before it reads, or retries a denied write.
 * <p>
 * It needs the Movie Database service on 8081 and Ollama on 11434, and it takes
 * tens of seconds, so it carries the "scenario" tag and the build leaves it out.
 * Run it deliberately:
 * <pre>mvn clean test -Pscenario</pre>
 */
@Tag("scenario")
class WatchListScenarioTest {

    private static final String MODEL = "qwen3:8b";
    private static final String ENDPOINT = "http://localhost:8081/graphql";
    /** The seeded account the course logs in as, from application.yaml. */
    private static final String USERNAME = "user";
    private static final String PASSWORD = "user123";
    private static final String LIST = "To watch this weekend";
    private static final String FILM = "The Matrix";

    @Test
    void agent_shouldReadTheListBeforeWritingToIt() throws Exception {
        AuthSession session = AuthSession.login(ENDPOINT, USERNAME, PASSWORD);

        // An earlier run may have left the film on the list. Removing it first means
        // the final assertion can only pass if this run added it.
        for (String entryId : filmEntries(session)) {
            graphql(session, "mutation($id: ID!) { removeWatchListItem(id: $id) { success } }",
                    Map.of("id", entryId));
        }
        assertThat(filmEntries(session)).isEmpty();

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
        List<String> toolCalls = new ArrayList<>();
        List<ToolCallback> callbacks = tools.stream()
                .map(tool -> recording(new GraphQlToolCallback(
                        tool, ENDPOINT, session, approveEverything, budget), toolCalls))
                .toList();

        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl("http://localhost:11434").build())
                .options(OllamaChatOptions.builder().model(MODEL).build())
                .build();

        new AgentRunner(model, MODEL).run(
                "add The Matrix to my To watch this weekend watchlist", callbacks, budget);

        // The assertions check the outcome. The agent read the watch lists before its
        // first write, because the list id comes from that read, and the film is on the list.
        assertThat(toolCalls).contains("myWatchLists", "addWatchListItem");
        assertThat(toolCalls.indexOf("myWatchLists")).isLessThan(toolCalls.indexOf("addWatchListItem"));
        assertThat(filmEntries(session)).hasSize(1);
    }

    /** The ids of the list's entries for the film, read with the session's token. */
    private static List<String> filmEntries(AuthSession session) {
        Map<?, ?> response = graphql(session, "{ myWatchLists { name items { id title { title } } } }", Map.of());
        List<String> ids = new ArrayList<>();
        if (response.get("data") instanceof Map<?, ?> data && data.get("myWatchLists") instanceof List<?> lists) {
            for (Object list : lists) {
                if (list instanceof Map<?, ?> watchList && LIST.equals(watchList.get("name"))
                        && watchList.get("items") instanceof List<?> items) {
                    for (Object entry : items) {
                        if (entry instanceof Map<?, ?> item && item.get("title") instanceof Map<?, ?> title
                                && FILM.equals(title.get("title"))) {
                            ids.add(String.valueOf(item.get("id")));
                        }
                    }
                }
            }
        }
        return ids;
    }

    private static Map<?, ?> graphql(AuthSession session, String document, Map<String, Object> variables) {
        Map<?, ?> response = RestClient.create()
                .post()
                .uri(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, session.bearerOrNull())
                .body(Map.of("query", document, "variables", variables))
                .retrieve()
                .body(Map.class);
        assertThat(response).isNotNull();
        assertThat(response.get("errors")).describedAs("GraphQL errors").isNull();
        return response;
    }

    /** Wraps a callback so the test can see which tools the model called, in order. */
    private static ToolCallback recording(ToolCallback callback, List<String> toolCalls) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return callback.getToolDefinition();
            }

            @Override
            public String call(String toolInput) {
                toolCalls.add(callback.getToolDefinition().name());
                return callback.call(toolInput);
            }
        };
    }
}
