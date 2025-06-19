package org.ntrloc.graph.graphql;

import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.graphql.impl.GraphQLSchemaGeneratorImpl;
import org.ntrloc.graph.db.schema.EntityDefinition;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class GraphQLSchemaGeneratorTest {

    private GraphQLSchemaGenerator generator;

    @BeforeEach
    public void init() {
        generator = new GraphQLSchemaGeneratorImpl();
    }

    @Test
    @DisplayName("Generate GraphQL types for entities")
    void testGenerateObjectTypes() {
        EntityDefinition photoEntity = new EntityDefinition();
        photoEntity.setName("Photo");

        TypeDefinitionRegistry registry = generator.generateTypeDefinitions(Set.of(photoEntity), Set.of());
        Optional<TypeDefinition> typeOpt = registry.getType("Photo");
        assertTrue(typeOpt.isPresent());
        TypeDefinition definition = typeOpt.get();
        assertTrue(definition instanceof ObjectTypeDefinition);
    }

}
