package com.graphqlguy.moviedb.agents.command;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Class 4: the server's own depth cap, demonstrated with no agent, no allow-list
 * and no callback in the way. Whatever the layers above allow, this one holds.
 */
@Component
public class ProbeDepthCommand implements AgentCommand {

    @Override
    public String name() {
        return "probe-depth";
    }

    @Override
    public int order() {
        return 41;
    }

    @Override
    public String summary() {
        return "send a pathological query straight to the server and read its refusal";
    }

    @Override
    public void run(List<String> args, CommandContext context) {
        // Movie -> cast -> movie -> cast ... : the schema's real cycle,
        // nested far beyond any sane query's needs.
        StringBuilder query = new StringBuilder("query { movie(id: \"1\")");
        int pairs = 9;
        for (int i = 0; i < pairs; i++) {
            query.append(" { cast { movie");
        }
        query.append(" { title ").append("} ".repeat(pairs * 2 + 2));
        String pathological = query.toString();

        System.out.println("query  : " + pathological);
        System.out.println();
        System.out.println(RestClient.create().post().uri(context.endpoint())
                .header("Content-Type", "application/json")
                .body(Map.of("query", pathological))
                .retrieve().body(String.class));
    }
}
