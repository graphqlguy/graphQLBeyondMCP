package com.graphqlguy.moviedb.agents.langchain;

import dev.langchain4j.service.SystemMessage;

/**
 * LangChain4j's signature idea: the agent is an interface, and the framework
 * synthesizes the implementation. Calling chat() runs the whole loop (model,
 * tools, memory) behind an ordinary Java method call. Compare this with the
 * hand-driven loop of Class 4 and Spring AI's ChatClient in Class 7: three
 * programming models, one identical tool layer underneath all of them.
 */
public interface MovieConcierge {

    @SystemMessage("""
            You are a concierge for the Movie Database. Use the tools to answer and to act;
            never invent ids, and never claim an action succeeded without a tool result
            proving it. Writes may require the human's approval; if a write is denied,
            accept the decision, explain it, and ask what they would like instead.
            When you need an id (a watchlist, a movie), find it with a read tool first.""")
    String chat(String message);
}
