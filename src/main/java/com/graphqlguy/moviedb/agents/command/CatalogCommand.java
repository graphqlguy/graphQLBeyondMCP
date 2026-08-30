package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import org.springframework.stereotype.Component;

import java.util.List;

/** Class 2: generate the tool catalog and print it, with what advertising it costs. */
@Component
public class CatalogCommand implements AgentCommand {

    @Override
    public String name() {
        return "catalog";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public String summary() {
        return "generate and print the tool catalog, optionally for one role";
    }

    @Override
    public String prompt() {
        return "role (blank for every allow-listed operation)";
    }

    @Override
    public void run(List<String> args, CommandContext context) throws Exception {
        String role = args.isEmpty() || args.get(0).isBlank() ? null : args.get(0);
        List<OperationTool> tools = context.tools(role);

        System.out.println("schema     : " + context.schemaPath());
        System.out.println("role       : " + (role == null ? "(all)" : role));
        System.out.println("tools      : " + tools.size());
        System.out.println("total cost : " + tools.stream()
                .mapToInt(OperationTool::tokenCount).sum()
                + " tokens to advertise this catalog (O200K_BASE)");
        System.out.println();
        for (OperationTool tool : tools) {
            System.out.printf("  %-20s %4d tokens  %s%n",
                    tool.name(), tool.tokenCount(),
                    tool.roles().isEmpty() ? "every role" : String.join(", ", tool.roles()));
        }

        // The definitions themselves are what a model reads, and they are long, so
        // they stay one keystroke away instead of filling the screen.
        while (true) {
            System.out.println();
            System.out.print("tool name for its full definition, `all` for the whole catalog,"
                    + " or blank to go back: ");
            String choice = context.console().hasNextLine()
                    ? context.console().nextLine().strip() : "";
            if (choice.isBlank()) {
                return;
            }
            System.out.println();
            if (choice.equalsIgnoreCase("all")) {
                System.out.println(context.generator().toJson(tools));
                continue;
            }
            List<OperationTool> one = tools.stream()
                    .filter(tool -> tool.name().equals(choice))
                    .toList();
            System.out.println(one.isEmpty()
                    ? "No tool called '" + choice + "' in this catalog."
                    : context.generator().toJson(one));
        }
    }
}
