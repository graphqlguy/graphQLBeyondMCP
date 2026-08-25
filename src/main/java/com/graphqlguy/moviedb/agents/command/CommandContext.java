package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.catalog.GraphQlToolCallback;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import com.graphqlguy.moviedb.agents.toolgen.AllowList;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import com.graphqlguy.moviedb.agents.toolgen.ToolCatalogGenerator;
import graphql.schema.GraphQLSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Scanner;

/**
 * Everything a command needs, built once at startup: the parsed schema, the
 * allow-list, the generator, the endpoint tools execute against, the environment,
 * and one console reader. The reader is shared deliberately: two Scanners on
 * System.in each buffer what they read, so the second one loses input the first
 * already pulled in.
 */
public record CommandContext(Environment env,
                             GraphQLSchema schema,
                             AllowList allowList,
                             ToolCatalogGenerator generator,
                             String schemaPath,
                             String endpoint,
                             Scanner console) {

    /** The catalog for one role, or every allow-listed operation when role is null. */
    public List<OperationTool> tools(String role) {
        return generator.generate(schema, allowList, role);
    }

    /** The role this run advertises, from configuration. */
    public String role() {
        return env.getProperty("agents.role", "concierge");
    }

    /** The configured username, blank when the run is anonymous. */
    public String username() {
        return env.getProperty("agents.auth.username", "");
    }

    /**
     * Class 4: the identity the run acts as. A real login against the service's own
     * mutation, never anything the model chose.
     */
    public AuthSession login() {
        return username().isBlank()
                ? AuthSession.anonymous()
                : AuthSession.login(endpoint, username(),
                        env.getProperty("agents.auth.password", ""));
    }

    /** Class 4: the ceilings this run may spend, from configuration. */
    public RunBudget budget() {
        return new RunBudget(
                env.getProperty("agents.budget.max-model-calls", Integer.class, 8),
                env.getProperty("agents.budget.max-tool-calls", Integer.class, 6));
    }

    /** Class 4: each generated tool wrapped in the callback carrying login, gate and budget. */
    public List<ToolCallback> callbacks(List<OperationTool> tools,
                                        AuthSession auth, ApprovalGate gate, RunBudget budget) {
        return tools.stream()
                .map(tool -> (ToolCallback) new GraphQlToolCallback(tool, endpoint, auth, gate, budget))
                .toList();
    }

    /** The chat model name this run should use. */
    public String modelName() {
        return env.getProperty("agents.model", "qwen3:8b");
    }
}
