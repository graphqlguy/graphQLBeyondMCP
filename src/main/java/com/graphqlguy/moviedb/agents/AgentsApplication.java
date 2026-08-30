package com.graphqlguy.moviedb.agents;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The starting point for the "GraphQL for AI Agents" course.
 *
 * This branch carries the project skeleton, the Maven dependencies, and the
 * committed schema snapshot, and nothing else. Every class adds to it: Class 2
 * builds the tool-catalog generator, Class 4 the safety layer, and the framework
 * track wires the result into LangChain4j, LangGraph4j and Spring AI.
 */
@SpringBootApplication
public class AgentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentsApplication.class, args);
    }
}
