package com.graphqlguy.moviedb.agents.toolgen;

import java.util.List;
import java.util.Map;

/**
 * One generated tool: everything a model provider needs to advertise it (name,
 * description, JSON Schema input) plus the artifact the callback executes (a complete,
 * persisted GraphQL operation document). The agent never writes GraphQL; it picks a
 * tool and fills arguments, and this document is what actually runs.
 *
 * @param name          the tool name the model sees; equal to the operation field name
 * @param description   taken from the schema description, the single source of truth
 * @param inputSchema   JSON Schema for the arguments, produced by JsonSchemaMapper
 * @param operationDocument the persisted GraphQL document the callback executes
 * @param roles         which agent roles may see this tool; empty means every role
 * @param tokenCount    what advertising this tool costs, measured with one fixed tokenizer
 */
public record OperationTool(String name, String description, Map<String, Object> inputSchema,
                            String operationDocument, List<String> roles, int tokenCount) {
}
