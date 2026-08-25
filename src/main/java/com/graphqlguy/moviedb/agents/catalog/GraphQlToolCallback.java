package com.graphqlguy.moviedb.agents.catalog;

import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * The bridge between a generated tool and Spring AI, and, since Class 4, the layer
 * where the safety controls actually live. Three things happen on every call, in
 * order: the run budget counts it (and stops runaway loops), a mutation must pass
 * the human approval gate, and the request carries the authenticated user's token,
 * so the server's own authorization still has the last word.
 * <p>
 * The controls sit here, at the execution layer, on purpose. A system prompt can
 * ask a model to behave; only the layer that performs the action can guarantee it.
 */
public class GraphQlToolCallback implements ToolCallback {

    private final OperationTool tool;
    private final String endpoint;
    private final AuthSession auth;
    private final ApprovalGate approvalGate;
    private final RunBudget budget;
    private final ObjectMapper json = new ObjectMapper();

    public GraphQlToolCallback(OperationTool tool, String endpoint, AuthSession auth,
                               ApprovalGate approvalGate, RunBudget budget) {
        this.tool = tool;
        this.endpoint = endpoint;
        this.auth = auth;
        this.approvalGate = approvalGate;
        this.budget = budget;
    }

    /** The Class 2 shape: a tool with none of the safety machinery, for direct calls. */
    public static GraphQlToolCallback plain(OperationTool tool, String endpoint) {
        return new GraphQlToolCallback(tool, endpoint, AuthSession.anonymous(), null, null);
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
        if (budget != null) {
            budget.countToolCall();
        }
        if (tool.mutation() && approvalGate != null && !approvalGate.approve(tool, toolInput)) {
            return "{\"denied\": \"The human declined this write. Do not retry it;"
                    + " explain the situation and ask what they would like instead.\"}";
        }
        Map<?, ?> variables = json.readValue(toolInput, Map.class);
        // Small models often flatten a mutation's single input object, sending its
        // fields as top-level arguments. The schema knows the intended shape, so the
        // repair is lossless: when the expected key is absent, wrap what arrived.
        if (tool.singleObjectArgument() != null
                && !variables.containsKey(tool.singleObjectArgument())) {
            variables = Map.of(tool.singleObjectArgument(), variables);
        }
        Map<String, Object> body = Map.of(
                "query", tool.operationDocument(),
                "variables", variables);
        RestClient.RequestBodySpec request = RestClient.create()
                .post()
                .uri(endpoint)
                .header("Content-Type", "application/json");
        if (auth.bearerOrNull() != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, auth.bearerOrNull());
        }
        String response = request.body(body).retrieve().body(String.class);
        return response == null ? "" : response;
    }
}
