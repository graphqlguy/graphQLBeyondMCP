package com.graphqlguy.moviedb.agents.toolgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The curation decision, kept in a file a human reviews in a pull request. One
 * operation per line, optionally followed by a colon and the roles allowed to see it:
 * <p>
 *   movie
 *   myWatchLists: concierge
 *   addWatchListItem: concierge
 * <p>
 * An operation absent from this file is invisible to every agent, which makes the
 * default deny. The format is deliberately plain text: the review diff IS the
 * security review, and nothing should stand between a reviewer and the meaning of
 * a changed line.
 */
public class AllowList {

    private final Map<String, List<String>> entries;

    private AllowList(Map<String, List<String>> entries) {
        this.entries = entries;
    }

    public static AllowList load(Path file) {
        Map<String, List<String>> entries = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(":", 2);
                List<String> roles = parts.length == 1 ? List.of()
                        : Arrays.stream(parts[1].split(",")).map(String::strip)
                                .filter(s -> !s.isEmpty()).toList();
                entries.put(parts[0].strip(), roles);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the allow-list at " + file, e);
        }
        return new AllowList(entries);
    }

    public boolean allows(String operationName, String role) {
        List<String> roles = entries.get(operationName);
        if (roles == null) {
            return false; // absent from the file means denied, for every role
        }
        return role == null || roles.isEmpty() || roles.contains(role);
    }

    public List<String> rolesFor(String operationName) {
        return entries.getOrDefault(operationName, List.of());
    }
}
