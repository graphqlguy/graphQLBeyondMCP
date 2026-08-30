package com.graphqlguy.moviedb.agents.toolgen;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks the schema's Query and Mutation roots and emits one tool per allow-listed
 * operation. Generation is a build-time activity: the catalog is a reviewable
 * artifact, regenerated when the schema changes, never assembled at request time.
 * <p>
 * The selection set each tool executes is generated with a deliberate default: the
 * scalar and enum fields of the returned object type, one level deep. That default
 * keeps generated operations cheap and predictable; anything richer (nested
 * relations, connections) is a curation decision a human makes by editing the
 * generated document, exactly as they would curate a persisted query.
 */
public class ToolCatalogGenerator {

    private final JsonSchemaMapper schemaMapper = new JsonSchemaMapper();
    private final ObjectMapper json = new ObjectMapper();
    private final Encoding encoding =
            Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.O200K_BASE);

    public List<OperationTool> generate(GraphQLSchema schema, AllowList allowList, String role) {
        List<OperationTool> tools = new ArrayList<>();
        addRoot(tools, schema.getQueryType(), "query", allowList, role);
        addRoot(tools, schema.getMutationType(), "mutation", allowList, role);
        return tools;
    }

    private void addRoot(List<OperationTool> tools, GraphQLObjectType root, String operationKind,
                         AllowList allowList, String role) {
        if (root == null) {
            return;
        }
        for (GraphQLFieldDefinition field : root.getFieldDefinitions()) {
            if (!allowList.allows(field.getName(), role)) {
                continue;
            }
            if (field.getDescription() == null || field.getDescription().isBlank()) {
                throw new IllegalStateException(
                        "Operation '" + field.getName() + "' is on the allow-list without a schema"
                        + " description. A tool the model cannot tell apart from its neighbours is"
                        + " worse than no tool, so describe it in the schema or remove it from the"
                        + " allow-list.");
            }
            String description = field.getDescription().strip();

            Map<String, Object> inputSchema = new LinkedHashMap<>();
            inputSchema.put("type", "object");
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (GraphQLArgument argument : field.getArguments()) {
                Map<String, Object> argSchema = schemaMapper.toJsonSchema(argument.getType());
                if (argument.getDescription() != null) {
                    argSchema.put("description", argument.getDescription().strip());
                }
                properties.put(argument.getName(), argSchema);
                if (JsonSchemaMapper.isRequired(argument.getType())) {
                    required.add(argument.getName());
                }
            }
            inputSchema.put("properties", properties);
            if (!required.isEmpty()) {
                inputSchema.put("required", required);
            }
            inputSchema.put("additionalProperties", false);

            String document = operationDocument(operationKind, field);
            int tokens = encoding.countTokens(
                    field.getName() + " " + description + " " + json.writeValueAsString(inputSchema));
            tools.add(new OperationTool(field.getName(), description, inputSchema,
                    document, allowList.rolesFor(field.getName()), tokens));
        }
    }

    /**
     * Builds the persisted operation the tool executes, forwarding every argument as
     * a variable with its original GraphQL type, so the server's own validation and
     * coercion still apply to whatever the model supplies.
     */
    private String operationDocument(String operationKind, GraphQLFieldDefinition field) {
        StringBuilder doc = new StringBuilder(operationKind).append(" Tool_").append(field.getName());
        if (!field.getArguments().isEmpty()) {
            List<String> varDefs = field.getArguments().stream()
                    .map(a -> "$" + a.getName() + ": " + GraphQLTypeUtil.simplePrint(a.getType()))
                    .toList();
            doc.append("(").append(String.join(", ", varDefs)).append(")");
        }
        doc.append(" { ").append(field.getName());
        if (!field.getArguments().isEmpty()) {
            List<String> args = field.getArguments().stream()
                    .map(a -> a.getName() + ": $" + a.getName())
                    .toList();
            doc.append("(").append(String.join(", ", args)).append(")");
        }
        doc.append(defaultSelection(field.getType()));
        doc.append(" }");
        return doc.toString();
    }

    private String defaultSelection(GraphQLType returnType) {
        GraphQLType unwrapped = GraphQLTypeUtil.unwrapAll(returnType);
        if (!(unwrapped instanceof GraphQLObjectType objectType)) {
            return ""; // scalar, enum, union, or interface returns: nothing generatable safely
        }
        List<String> scalarFields = objectType.getFieldDefinitions().stream()
                .filter(f -> {
                    GraphQLType fieldType = GraphQLTypeUtil.unwrapAll(f.getType());
                    return fieldType instanceof GraphQLScalarType || fieldType instanceof GraphQLEnumType;
                })
                .map(GraphQLFieldDefinition::getName)
                .toList();
        if (scalarFields.isEmpty()) {
            return "";
        }
        return " { " + String.join(" ", scalarFields) + " }";
    }

    public String toJson(List<OperationTool> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OperationTool tool : tools) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.put("inputSchema", tool.inputSchema());
            entry.put("operation", tool.operationDocument());
            entry.put("roles", tool.roles());
            entry.put("tokens", tool.tokenCount());
            out.add(entry);
        }
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(out);
    }
}
