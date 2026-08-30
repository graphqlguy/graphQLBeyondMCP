package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.toolgen.AllowList;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import com.graphqlguy.moviedb.agents.toolgen.ToolCatalogGenerator;
import graphql.schema.GraphQLSchema;
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
}
