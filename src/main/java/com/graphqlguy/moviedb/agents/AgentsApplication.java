package com.graphqlguy.moviedb.agents;

import com.graphqlguy.moviedb.agents.command.AgentCommand;
import com.graphqlguy.moviedb.agents.command.CommandContext;
import com.graphqlguy.moviedb.agents.toolgen.AllowList;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

/**
 * The entry point, and deliberately thin: it builds what every command shares,
 * then hands over to one {@link AgentCommand}. Each class of this course adds a
 * command class, so the file you are reading stays the same size throughout.
 * <p>
 * Run it with a command name and its arguments, or with none at all to choose
 * from a menu. Configuration lives in application.yaml under agents.*: the schema
 * file, the allow-list file, and the GraphQL endpoint the callbacks post to.
 */
@SpringBootApplication
public class AgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentsApplication.class, args);
    }

    @Bean
    CommandLineRunner commands(Environment env, List<AgentCommand> commands) {
        return args -> {
            String schemaPath = env.getProperty("agents.schema", "src/main/resources/schema-snapshot.graphqls");
            String allowListPath = env.getProperty("agents.allow-list", "src/main/resources/tool-allowlist.txt");
            String endpoint = env.getProperty("agents.endpoint", "http://localhost:8080/graphql");

            GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
                    new SchemaParser().parse(Files.readString(Path.of(schemaPath))));
            CommandContext context = new CommandContext(env, schema,
                    AllowList.load(Path.of(allowListPath)), new ToolCatalogGenerator(),
                    schemaPath, endpoint, new Scanner(System.in));

            List<AgentCommand> ordered = commands.stream()
                    .sorted(Comparator.comparingInt(AgentCommand::order))
                    .toList();

            if (args.length == 0) {
                runFromMenu(ordered, context);
                return;
            }
            String requested = args[0];
            AgentCommand command = ordered.stream()
                    .filter(c -> c.name().equals(requested))
                    .findFirst()
                    .orElse(null);
            if (command == null) {
                System.out.println("Unknown command: " + requested);
                printMenu(ordered);
                return;
            }
            command.run(List.of(Arrays.copyOfRange(args, 1, args.length)), context);
        };
    }

    /**
     * Started with no arguments: list what this build can do, run the choice, and
     * come back. The application starts once and stays up, so running the same task
     * through several frameworks is a matter of keystrokes.
     */
    private static void runFromMenu(List<AgentCommand> commands, CommandContext context) {
        while (true) {
            printMenu(commands);
            System.out.print("> ");
            String choice = context.console().hasNextLine()
                    ? context.console().nextLine().strip() : "q";
            if (choice.isBlank() || choice.equalsIgnoreCase("q") || choice.equalsIgnoreCase("quit")) {
                System.out.println("Bye.");
                return;
            }
            AgentCommand command = byNumberOrName(commands, choice);
            if (command == null) {
                System.out.println("No command called '" + choice + "'.");
                continue;
            }
            try {
                runWithPrompt(command, context);
            } catch (Exception failure) {
                // A failed command returns to the menu. An agent run fails often
                // enough that restarting the application each time would teach
                // patience rather than agents.
                System.out.println();
                System.out.println("The command failed: " + failure);
            }
        }
    }

    /** Ask for whatever the chosen command needs, then run it. */
    private static void runWithPrompt(AgentCommand command, CommandContext context) throws Exception {
        List<String> arguments = new ArrayList<>();
        if (!command.prompt().isBlank()) {
            System.out.print(command.prompt() + ": ");
            String line = context.console().hasNextLine()
                    ? context.console().nextLine().strip() : "";
            if (!line.isBlank()) {
                arguments.addAll(Arrays.asList(line.split(" ")));
            }
        }
        System.out.println();
        command.run(arguments, context);
    }

    private static void printMenu(List<AgentCommand> commands) {
        System.out.println();
        System.out.println("Movie Database agents. Choose a command by number or name, or q to quit:");
        for (int i = 0; i < commands.size(); i++) {
            System.out.printf("  %2d  %-14s %s%n",
                    i + 1, commands.get(i).name(), commands.get(i).summary());
        }
    }

    private static AgentCommand byNumberOrName(List<AgentCommand> commands, String choice) {
        for (AgentCommand command : commands) {
            if (command.name().equals(choice)) {
                return command;
            }
        }
        try {
            int index = Integer.parseInt(choice) - 1;
            return index >= 0 && index < commands.size() ? commands.get(index) : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
