package com.graphqlguy.moviedb.agents.toolgen;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates GraphQL input types into JSON Schema, the language every major model
 * provider expects tool parameters in. The mapping is mechanical, which is the whole
 * point of this class of the course: the schema already contains everything a tool
 * definition needs, so producing one is translation, never authoring.
 * <p>
 * The rules, each mirroring a GraphQL concept:
 * - scalars map to JSON Schema primitives; ID becomes a string, because that is how
 *   it travels in JSON regardless of what the server stores.
 * - enums become string types with an explicit value list, so the model can only
 *   choose values that exist.
 * - non-null wrappers do not change the type; they put the field on the parent's
 *   "required" list, which is where JSON Schema expresses obligation.
 * - lists become arrays of the mapped element type.
 * - input objects map recursively to nested object schemas.
 */
public class JsonSchemaMapper {

    public Map<String, Object> toJsonSchema(GraphQLInputType type) {
        return mapType(unwrapNonNull(type));
    }

    /** Non-null says the value is required, which the caller records; the type is inside. */
    public static GraphQLInputType unwrapNonNull(GraphQLInputType type) {
        if (type instanceof GraphQLNonNull nonNull) {
            return (GraphQLInputType) nonNull.getWrappedType();
        }
        return type;
    }

    public static boolean isRequired(GraphQLInputType type) {
        return type instanceof GraphQLNonNull;
    }

    private Map<String, Object> mapType(GraphQLType type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        switch (type) {
            case GraphQLScalarType scalar -> schema.put("type", switch (scalar.getName()) {
                case "Int" -> "integer";
                case "Float" -> "number";
                case "Boolean" -> "boolean";
                default -> "string"; // String, ID, and custom scalars travel as strings
            });
            case GraphQLEnumType enumType -> {
                schema.put("type", "string");
                schema.put("enum", enumType.getValues().stream()
                        .map(GraphQLEnumValueDefinition::getName).toList());
                if (enumType.getDescription() != null) {
                    schema.put("description", enumType.getDescription().strip());
                }
            }
            case GraphQLList list -> {
                schema.put("type", "array");
                schema.put("items", mapType(unwrapNonNull((GraphQLInputType) list.getWrappedType())));
            }
            case GraphQLInputObjectType inputObject -> {
                schema.put("type", "object");
                Map<String, Object> properties = new LinkedHashMap<>();
                List<String> required = new ArrayList<>();
                for (GraphQLInputObjectField field : inputObject.getFieldDefinitions()) {
                    Map<String, Object> fieldSchema = mapType(unwrapNonNull(field.getType()));
                    if (field.getDescription() != null) {
                        fieldSchema.put("description", field.getDescription().strip());
                    }
                    properties.put(field.getName(), fieldSchema);
                    if (isRequired(field.getType())) {
                        required.add(field.getName());
                    }
                }
                schema.put("properties", properties);
                if (!required.isEmpty()) {
                    schema.put("required", required);
                }
                schema.put("additionalProperties", false);
            }
            case null, default -> throw new IllegalStateException(
                    "Unmapped input type: " + type + ". Every input type a tool exposes"
                            + " must have a JSON Schema translation.");
        }
        return schema;
    }
}
