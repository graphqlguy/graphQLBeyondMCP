package com.graphqlguy.moviedb.agents.catalog;

import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * The bridge between a generated tool and Spring AI. ToolCallback is the contract
 * every Spring AI chat model consumes: getToolDefinition() is what gets advertised
 * to the model, and call() runs when the model chooses the tool.
 * <p>
 * The call itself is deliberately unexciting: the model's JSON arguments become
 * GraphQL variables on the persisted operation document, posted to the endpoint.
 * The model chose an operation by name and filled typed blanks; it composed
 * nothing, which is precisely the safety property the allow-list pattern buys.
 */
public class GraphQlToolCallback implements ToolCallback {

    private final OperationTool tool;
    private final String endpoint;
    private final ObjectMapper json = new ObjectMapper();

    public GraphQlToolCallback(OperationTool tool, String endpoint) {
        this.tool = tool;
        this.endpoint = endpoint;
    }

    @Override
    public @NonNull ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(json.writeValueAsString(tool.inputSchema()))
                .build();
    }

    @Override
    public @NonNull String call(@NonNull String toolInput) {
        Map<?, ?> variables = json.readValue(toolInput, Map.class);
        Map<String, Object> body = Map.of(
                "query", tool.operationDocument(),
                "variables", variables);
        String response = RestClient.create()
                .post()
                .uri(endpoint)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);
        return response == null ? "" : response;
    }
}
