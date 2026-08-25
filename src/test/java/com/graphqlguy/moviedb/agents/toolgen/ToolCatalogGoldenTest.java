package com.graphqlguy.moviedb.agents.toolgen;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The drift gate Class 2 promised, as a test. The catalog is regenerated from
 * the committed schema snapshot and compared byte for byte against the golden
 * copy; when the schema (or the generator) changes, this test fails, and the
 * fix is a deliberate regeneration reviewed in the same pull request as the
 * change that caused it. "The schema and the catalog disagree" becomes a red
 * build a human resolves instead of a surprise an agent discovers.
 */
class ToolCatalogGoldenTest {

    @Test
    void generatedCatalogMatchesTheCommittedGolden() throws Exception {
        var schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
                new SchemaParser().parse(Files.readString(
                        Path.of("src/main/resources/schema-snapshot.graphqls"))));
        var allowList = AllowList.load(Path.of("src/main/resources/tool-allowlist.txt"));
        var generated = new ToolCatalogGenerator().generate(schema, allowList, null);
        String json = new ToolCatalogGenerator().toJson(generated);

        Path golden = Path.of("src/test/resources/catalog-golden.json");
        assertThat(json.strip()).isEqualTo(Files.readString(golden).strip());
    }

    @Test
    void rolesNarrowTheCatalog() throws Exception {
        var schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
                new SchemaParser().parse(Files.readString(
                        Path.of("src/main/resources/schema-snapshot.graphqls"))));
        var allowList = AllowList.load(Path.of("src/main/resources/tool-allowlist.txt"));
        var generator = new ToolCatalogGenerator();
        assertThat(generator.generate(schema, allowList, "support")).hasSize(5);
        assertThat(generator.generate(schema, allowList, "concierge")).hasSize(7);
        assertThat(generator.generate(schema, allowList, "concierge").stream()
                .filter(OperationTool::mutation)).hasSize(1);
    }
}
