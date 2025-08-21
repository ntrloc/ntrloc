package org.ntrloc.graph.graphql.impl;

import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.GraphQLSchemaGenerator;
import org.ntrloc.graph.graphql.GraphQLTypeRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
class GraphQLTypeRegistrarImpl implements GraphQLTypeRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQLTypeRegistrarImpl.class);

    private GraphQLSchemaGenerator schemaGenerator;

    GraphQLTypeRegistrarImpl(GraphQLSchemaGenerator schemaGenerator) {
        this.schemaGenerator = schemaGenerator;
    }

    @Override
    public TypeDefinitionRegistry getTypeDefinitionRegistry(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        TypeDefinitionRegistry registry = new TypeDefinitionRegistry();

        GraphqlDefinitions graphqlDefinitions = schemaGenerator.generateTypeDefinitions(entityDefinitions, relationshipDefinitions);

        for (DirectiveDefinition def: graphqlDefinitions.getDirectiveDefinitions()) {
            registry.add(def);
        }

        for (ObjectTypeDefinition def: graphqlDefinitions.getObjectTypeDefinitions()) {
            LOG.info("Registering entity definition: {}", def.getName());
            registry.add(def);
        }

        for (InputObjectTypeDefinition def: graphqlDefinitions.getInputObjectTypeDefinitions()) {
            LOG.info("Registering entity input definition {}", def);
            registry.add(def);
        }



        /*
        if (!entityDefinitions.isEmpty()) {
            registry.add(getQueryExtensions(entityDefinitions));
        }

        if (!entityInputTypes.isEmpty()) {
            List<FieldDefinition> mutationFields = entityInputTypes.entrySet().stream().map(entry -> {
                ObjectTypeDefinition typeDefinition = entry.getKey();
                InputObjectTypeDefinition inputObjectTypeDefinition = entry.getValue();
                return FieldDefinition.newFieldDefinition()
                        .name(String.format("add%s", typeDefinition.getName()))
                        .inputValueDefinition(InputValueDefinition.newInputValueDefinition()
                                .name("input")
                                .type(new TypeName(inputObjectTypeDefinition.getName()))
                                .build())
                        .type(new TypeName(typeDefinition.getName()))
                        .build();
            }).toList();

            ObjectTypeDefinition mutationType = ObjectTypeDefinition.newObjectTypeDefinition()
                    .name("Mutation")
                    .fieldDefinitions(mutationFields)
                    .build();
            registry.add(mutationType);
        }

         */

        return registry;
    }

}
