package com.graphqlguy.moviedb.agents.command;

import com.graphqlguy.moviedb.agents.incremental.DeferDemo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Class 9: graphql-java's experimental @defer, run against a self-contained schema
 * so the timestamps prove the initial payload arrives before the deferred one.
 * This one needs neither the service nor a model.
 */
@Component
public class DeferDemoCommand implements AgentCommand {

    @Override
    public String name() {
        return "defer-demo";
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public String summary() {
        return "graphql-java's experimental @defer, timestamped";
    }

    @Override
    public void run(List<String> args, CommandContext context) throws Exception {
        new DeferDemo().run();
    }
}
