package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.catalog.GraphQlToolCallback;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import org.springframework.stereotype.Component;

import java.util.List;

/** Class 2: execute one generated tool directly, proving the wiring with no model in the loop. */
@Component
public class CallCommand implements AgentCommand {

    @Override
    public String name() {
        return "call";
    }

    @Override
    public int order() {
        return 21;
    }

    @Override
    public String summary() {
        return "execute one generated tool against the endpoint, with no model involved";
    }

    @Override
    public String prompt() {
        return "tool name and JSON arguments, e.g. movie {\"id\":\"1\"}";
    }

    @Override
    public void run(List<String> args, CommandContext context) throws Exception {
        if (args.size() < 2) {
            System.out.println("call needs a tool name and a JSON argument object");
            return;
        }
        String toolName = args.get(0);
        String jsonArgs = String.join(" ", args.subList(1, args.size()));
        OperationTool tool = context.tools(null).stream()
                .filter(t -> t.name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No allow-listed tool named '" + toolName + "'"));
        System.out.println("operation : " + tool.operationDocument());
        System.out.println("arguments : " + jsonArgs);
        System.out.println();
        System.out.println(new GraphQlToolCallback(tool, context.endpoint()).call(jsonArgs));
    }
}
