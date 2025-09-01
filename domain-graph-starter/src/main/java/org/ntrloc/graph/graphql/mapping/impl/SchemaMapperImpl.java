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
import org.ntrloc.graph.graphql.mapping.input.EntityInputObjectTypeMapping;
import org.ntrloc.graph.graphql.mapping.input.EntityInputObjectTypesMapper;
import org.ntrloc.graph.graphql.mapping.output.EntityObjectTypeMapping;
import org.ntrloc.graph.graphql.mapping.output.EntityObjectTypesMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SchemaMapperImpl implements SchemaMapper {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMapperImpl.class);

    private static final String EXECUTE_FIELD_NAME = "execute";
    private static final String INPUTS_FIELD_NAME = "inputs";

    private EntityInputObjectTypesMapper inputTypesMapper;
    private EntityObjectTypesMapper outputTypesMapper;

    private Map<String, InputObjectTypeDefinition> inputTypeDefinitions;
    private Map<String, ObjectTypeDefinition> outputTypeDefinitions;
    private Map<String, ObjectTypeExtensionDefinition> extensionDefinitions;

    private Map<String, EntityInputObjectTypeMapping> entityInputTypes;
    private Map<String, EntityObjectTypeMapping> entityOutputTypes;

    private MutationObjectTypeMapping mutationObjectTypeMapping;

    public SchemaMapperImpl(EntityInputObjectTypesMapper inputTypesMapper, EntityObjectTypesMapper outputTypesMapper) {
        this.inputTypesMapper = inputTypesMapper;
        this.outputTypesMapper = outputTypesMapper;
    }

    public void mapSchema(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {

        inputTypeDefinitions = new HashMap<>();
        inputTypeDefinitions.putAll(inputTypesMapper.mapInputObjectTypes(entityDefinitions, relationshipDefinitions));
        entityInputTypes = inputTypesMapper.getEntityMapping();

        outputTypeDefinitions = new HashMap<>();
        outputTypeDefinitions.putAll(outputTypesMapper.mapObjectTypes(entityDefinitions, relationshipDefinitions));
        entityOutputTypes = outputTypesMapper.getEntityOutputTypes();

        // create the mutation type to allow mutations on all entity inputs
        if (!entityInputTypes.isEmpty() && !entityOutputTypes.isEmpty()) {
            this.mutationObjectTypeMapping = new MutationObjectTypeMapping(entityInputTypes, entityOutputTypes);
            List<ObjectTypeDefinition> mutationDefinitions = mutationObjectTypeMapping.getObjectTypeDefinitions();
            for (ObjectTypeDefinition mutationDefinition : mutationDefinitions) {
                outputTypeDefinitions.put(mutationDefinition.getName(), mutationDefinition);
            }
        }

        extensionDefinitions = new HashMap<>();
        if (!entityOutputTypes.isEmpty()) {
            var queryType = createQueryTypeExtension(entityOutputTypes);
            extensionDefinitions.put(queryType.getName(), queryType);
        }

        // extend the query type to allow queries all entity outputs
        LOG.info("maybe?");
    }

    public List<InputObjectTypeDefinition> getInputTypes() {
        return inputTypeDefinitions.values().stream().toList();
    }

    public List<ObjectTypeDefinition> getOutputTypes() {
        return outputTypeDefinitions.values().stream().toList();
    }

    public List<ObjectTypeExtensionDefinition> getExtensionTypes() {
        return extensionDefinitions.values().stream().toList();
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
