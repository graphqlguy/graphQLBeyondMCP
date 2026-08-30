package com.graphqlguy.moviedb.agents;

import com.graphqlguy.moviedb.agents.catalog.GraphQlToolCallback;
import com.graphqlguy.moviedb.agents.toolgen.AllowList;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import com.graphqlguy.moviedb.agents.toolgen.ToolCatalogGenerator;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Class 2's command-line entry point. Commands:
 * <pre>{@code
 *   catalog [role]         generate and print the tool catalog (optionally for one role)
 *   call <tool> <json>     execute one generated tool directly, proving the wiring
 *                          without any model in the loop
 * }</pre>
 * Configuration lives in application.yaml under agents.*: the schema file, the
 * allow-list file, and the GraphQL endpoint the callbacks post to.
 */
@SpringBootApplication
public class AgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentsApplication.class, args);
    }

    @Bean
    CommandLineRunner commands(Environment env) {
        return args -> {
            if (args.length == 0) {
                System.out.println("""
                        Usage:
                          catalog [role]       generate and print the tool catalog
                          call <tool> <json>   execute one generated tool against the endpoint""");
                return;
            }
            String schemaPath = env.getProperty("agents.schema", "src/main/resources/schema-snapshot.graphqls");
            String allowListPath = env.getProperty("agents.allow-list", "src/main/resources/tool-allowlist.txt");
            String endpoint = env.getProperty("agents.endpoint", "http://localhost:8080/graphql");

            GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
                    new SchemaParser().parse(Files.readString(Path.of(schemaPath))));
            AllowList allowList = AllowList.load(Path.of(allowListPath));
            ToolCatalogGenerator generator = new ToolCatalogGenerator();

            switch (args[0]) {
                case "catalog" -> {
                    String role = args.length > 1 ? args[1] : null;
                    List<OperationTool> tools = generator.generate(schema, allowList, role);
                    System.out.println("schema     : " + schemaPath);
                    System.out.println("role       : " + (role == null ? "(all)" : role));
                    System.out.println("tools      : " + tools.size());
                    System.out.println("total cost : " + tools.stream()
                            .mapToInt(OperationTool::tokenCount).sum()
                            + " tokens to advertise this catalog (O200K_BASE)");
                    System.out.println();
                    System.out.println(generator.toJson(tools));
                }
                case "call" -> {
                    if (args.length < 3) {
                        System.out.println("call needs a tool name and a JSON argument object");
                        return;
                    }
                    String toolName = args[1];
                    String jsonArgs = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    List<OperationTool> tools = generator.generate(schema, allowList, null);
                    OperationTool tool = tools.stream()
                            .filter(t -> t.name().equals(toolName))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "No allow-listed tool named '" + toolName + "'"));
                    System.out.println("operation : " + tool.operationDocument());
                    System.out.println("arguments : " + jsonArgs);
                    System.out.println();
                    String result = new GraphQlToolCallback(tool, endpoint).call(jsonArgs);
                    System.out.println(result);
                }
                default -> System.out.println("Unknown command: " + args[0]);
            }
        };
    }
}
