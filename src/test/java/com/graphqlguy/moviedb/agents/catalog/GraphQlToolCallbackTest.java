package com.graphqlguy.moviedb.agents.catalog;

import com.graphqlguy.moviedb.agents.auth.AuthSession;
import com.graphqlguy.moviedb.agents.safety.ApprovalGate;
import com.graphqlguy.moviedb.agents.safety.RunBudget;
import com.graphqlguy.moviedb.agents.toolgen.OperationTool;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The safety behaviors of Class 4, as assertions. The endpoint is a ten-line
 * embedded stub recording what actually went over the wire, because the
 * behaviors under test live BEFORE and AROUND the network call: the denial
 * that returns without any request, the budget that throws an exception on
 * the call past its ceiling, and the tolerant unwrap whose repaired shape
 * must be what the server receives.
 */
class GraphQlToolCallbackTest {

    private HttpServer server;
    private String endpoint;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    /**
     * The one write in the catalog, as the generator would produce it. The last two
     * components carry the behaviour under test: {@code mutation} sends it through the
     * approval gate, and {@code singleObjectArgument} names the argument the tolerant
     * unwrap wraps a flattened payload into.
     */
    private static final OperationTool ADD_WATCH_LIST_ITEM = new OperationTool(
            "addWatchListItem",
            "adds an item to one of the caller's watch lists",
            Map.of("type", "object"),
            "mutation Tool_addWatchListItem($input: AddWatchListItemInput!)"
                    + " { addWatchListItem(input: $input) { id } }",
            List.of(),      // roles: every role may see it in this test
            0,              // token count: unused here
            true,           // mutation, so the approval gate applies
            "input");       // the lone input-object argument

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/graphql", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        endpoint = "http://localhost:" + server.getAddress().getPort() + "/graphql";
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void aDeniedWrite_shouldAnswerTheModelWithoutTouchingTheServer() {
        ApprovalGate denyEverything = alwaysDeny();
        var callback = new GraphQlToolCallback(ADD_WATCH_LIST_ITEM, endpoint,
                AuthSession.anonymous(), denyEverything, null);

        String result = callback.call("{\"input\":{}}");

        assertThat(result).contains("denied").contains("Do not retry");
        assertThat(lastBody.get()).isNull(); // the stub server received no request
    }

    @Test
    void budget_shouldThrowAnExceptionOnTheCallPastItsCeiling() {
        RunBudget budget = new RunBudget(8, 2);
        var callback = new GraphQlToolCallback(ADD_WATCH_LIST_ITEM, endpoint,
                AuthSession.anonymous(), null, budget);

        callback.call("{\"input\":{}}");
        callback.call("{\"input\":{}}");

        assertThatThrownBy(() -> callback.call("{\"input\":{}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget exceeded");
    }

    /**
     * A gate that refuses everything, reading from an empty source: the refusal happens
     * before any prompt is answered, so no test ever waits on input.
     */
    private static ApprovalGate alwaysDeny() {
        return new ApprovalGate(new Scanner("")) {
            @Override
            public boolean approve(String toolName, String operationDocument, String jsonArguments) {
                return false;
            }
        };
    }

    @Test
    void flattenedSingleInputArguments_shouldBeWrappedBeforeTheWire() {
        var callback = new GraphQlToolCallback(ADD_WATCH_LIST_ITEM, endpoint,
                AuthSession.anonymous(), null, null);

        callback.call("{\"watchListId\":\"2\",\"titleId\":\"5\"}"); // the flat shape small models send

        assertThat(lastBody.get())
                .contains("\"input\":{")   // the wrap happened
                .contains("\"watchListId\":\"2\"");
    }
}
