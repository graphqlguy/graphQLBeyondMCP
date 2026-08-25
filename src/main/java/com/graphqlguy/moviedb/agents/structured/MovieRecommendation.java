package com.graphqlguy.moviedb.agents.structured;

/**
 * The Java mirror of the MovieCard fragment, plus one field the data cannot
 * supply. The first four components correspond one to one with the fragment's
 * selections, so the agent's typed output and the tool's typed result are the
 * same shape in two dialects; "reason" is the model's own contribution, the
 * part that justifies involving a model at all.
 */
public record MovieRecommendation(String id, String title, int releaseYear,
                                  double rating, String reason) {
}
