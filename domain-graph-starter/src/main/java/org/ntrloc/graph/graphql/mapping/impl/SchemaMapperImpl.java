package org.ntrloc.graph.graphql.mapping.impl;

import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.db.language.mutation.EntityMutation;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.MutationObjectTypeMapping;
import org.ntrloc.graph.graphql.mapping.SchemaMapper;
import org.ntrloc.graph.graphql.mapping.output.EntityObjectTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SchemaMapperImpl implements SchemaMapper {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMapperImpl.class);

    private List<InputObjectTypeDefinition> inputTypeDefinitions;
    private List<ObjectTypeDefinition> outputTypeDefinitions;
    private List<ObjectTypeExtensionDefinition> extensionDefinitions;

    private MutationObjectTypeMapping mutationObjectTypeMapping;

    public SchemaMapperImpl() {
    }

    public void mapSchema(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        mutationObjectTypeMapping = new MutationObjectTypeMapping(entityDefinitions, relationshipDefinitions);

        // extend the query type to allow queries for all entity outputs
        inputTypeDefinitions = mutationObjectTypeMapping.getInputObjectTypeDefinitions().stream()
                .collect(Collectors.toMap(InputObjectTypeDefinition::getName, inputObjectTypeDefinition -> inputObjectTypeDefinition, (existingValue, newValue) -> existingValue))
                .values().stream().toList();

        outputTypeDefinitions = mutationObjectTypeMapping.getObjectTypeDefinitions().stream()
                .collect(Collectors.toMap(ObjectTypeDefinition::getName, def -> def, (existingValue, newValue) -> existingValue))
                        .values().stream().toList();

        extensionDefinitions = new ArrayList<>();
    }

    public List<InputObjectTypeDefinition> getInputTypes() {
        return inputTypeDefinitions;
    }

    public List<ObjectTypeDefinition> getOutputTypes() {
        return outputTypeDefinitions;
    }

    public List<ObjectTypeExtensionDefinition> getExtensionTypes() {
        return extensionDefinitions;
    }

    private ObjectTypeExtensionDefinition createQueryTypeExtension(Map<String, EntityObjectTypeMapping> entityOutputTypes) {
        List<FieldDefinition> fieldDefinitions = entityOutputTypes.entrySet().stream().map(key -> {
            String entityName = key.getKey();
            EntityObjectTypeMapping entityOutput = key.getValue();
            return FieldDefinition.newFieldDefinition()
                    .name(entityName)
                    .type(new NonNullType(new ListType(new NonNullType(new TypeName(entityOutput.getGraphQlTypeName())))))
                    .build();
        }).toList();

        if (!fieldDefinitions.isEmpty()) {
           return ObjectTypeExtensionDefinition.newObjectTypeExtensionDefinition()
                    .name("Query")
                    .fieldDefinitions(fieldDefinitions)
                    .build();
        } else {
            return null;
        }
    }

    public Map<String, List<EntityMutation>> parseEntityMutations(Field mutationField) {
        return mutationObjectTypeMapping.parseEntityMutations(mutationField);
    }

}
