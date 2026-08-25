package com.graphqlguy.moviedb.agents.auth;

import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * The agent acts on behalf of a user, and the user's identity comes from a real
 * login, never from an argument the model controls. This session logs in through
 * the service's own login mutation and holds the JWT; every tool call carries it.
 * <p>
 * The principle behind the plumbing: if the model could choose the user (a userId
 * argument, a name in the prompt), a confused or manipulated agent could act as
 * anyone. Because identity rides in the Authorization header and the server scopes
 * every watchlist operation to the authenticated user, the worst a confused agent
 * can do is act wrongly as the one user it already is.
 */
public class AuthSession {

    private static final String LOGIN_DOCUMENT =
            "mutation Login($input: LoginInput!) { login(input: $input) { token } }";

    private final String token;

    private AuthSession(String token) {
        this.token = token;
    }

    public static AuthSession login(String endpoint, String username, String password) {
        Map<?, ?> response = RestClient.create()
                .post()
                .uri(endpoint)
                .header("Content-Type", "application/json")
                .body(Map.of(
                        "query", LOGIN_DOCUMENT,
                        "variables", Map.of("input", Map.of(
                                "username", username, "password", password))))
                .retrieve()
                .body(Map.class);
        Object data = response == null ? null : response.get("data");
        if (data instanceof Map<?, ?> dataMap
                && dataMap.get("login") instanceof Map<?, ?> login
                && login.get("token") instanceof String jwt) {
            return new AuthSession(jwt);
        }
        throw new IllegalStateException(
                "Login as '" + username + "' failed; the service answered: " + response);
    }

    /** No credentials configured: tools run anonymously, and protected ones will be refused by the server. */
    public static AuthSession anonymous() {
        return new AuthSession(null);
    }

    public String bearerOrNull() {
        return token == null ? null : "Bearer " + token;
    }
}
