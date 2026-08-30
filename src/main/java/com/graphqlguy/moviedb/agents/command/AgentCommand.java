package com.graphqlguy.moviedb.agents.command;

import java.util.List;

/**
 * One command the application can run. Each class of this course adds one, and the
 * application discovers them as beans, so a new command means a new file rather
 * than a longer switch statement.
 */
public interface AgentCommand {

    /** The name typed on the command line, or chosen from the menu. */
    String name();

    /** One line describing what the command does, shown in the menu. */
    String summary();

    /** What the command asks for when the menu runs it, or an empty string if it takes no argument. */
    default String prompt() {
        return "";
    }

    /** Where the command sits in the menu: the class that introduces it, times ten. */
    int order();

    void run(List<String> args, CommandContext context) throws Exception;
}
