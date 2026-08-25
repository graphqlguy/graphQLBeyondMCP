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

    private final OperationTool writeTool = new OperationTool(
            "addWatchListItem", "adds an item",
            Map.of("type", "object"),
            "mutation Tool_addWatchListItem($input: AddWatchListItemInput!) { addWatchListItem(input: $input) { id } }",
            List.of(), 0, true, "input");

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
    void aDeniedWriteAnswersTheModelWithoutTouchingTheServer() {
        ApprovalGate denyEverything = new ApprovalGate() {
            @Override
            public boolean approve(String tool, String operation, String args) {
                return false;
            }
        };
        var callback = new GraphQlToolCallback(writeTool, endpoint,
                AuthSession.anonymous(), denyEverything, null);

        String result = callback.call("{\"input\":{}}");

        assertThat(result).contains("denied").contains("Do not retry");
        assertThat(lastBody.get()).isNull(); // the request never left the building
    }

    @Test
    void theBudgetThrowsAnExceptionOnTheCallPastItsCeiling() {
        RunBudget budget = new RunBudget(8, 2);
        var callback = new GraphQlToolCallback(writeTool, endpoint,
                AuthSession.anonymous(), null, budget);

        callback.call("{\"input\":{}}");
        callback.call("{\"input\":{}}");

        assertThatThrownBy(() -> callback.call("{\"input\":{}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget exceeded");
    }

    @Test
    void flattenedSingleInputArgumentsAreWrappedBeforeTheWire() {
        var callback = new GraphQlToolCallback(writeTool, endpoint,
                AuthSession.anonymous(), null, null);

        callback.call("{\"watchListId\":\"2\",\"titleId\":\"5\"}"); // the flat shape small models send

        assertThat(lastBody.get())
                .contains("\"input\":{")   // the wrap happened
                .contains("\"watchListId\":\"2\"");
    }
}
