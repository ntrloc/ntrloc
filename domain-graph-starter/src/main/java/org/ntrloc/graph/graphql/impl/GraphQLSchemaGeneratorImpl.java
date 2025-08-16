package org.ntrloc.graph.graphql.impl;

import graphql.language.Description;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.TypeName;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.GraphQLSchemaGenerator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GraphQLSchemaGeneratorImpl implements GraphQLSchemaGenerator {

    private static final Logger LOG = LogManager.getLogger(GraphQLSchemaGeneratorImpl.class);

    @Override
    public TypeDefinitionRegistry generateTypeDefinitions(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        TypeDefinitionRegistry registry = new TypeDefinitionRegistry();

        Map<ObjectTypeDefinition, InputObjectTypeDefinition> entityInputTypes = new HashMap<>();

        for (EntityDefinition entityDefinition: entityDefinitions) {
            EntityGraphqlDefinition definition = getEntityTypeDefinition(entityDefinition);

            LOG.info("Registering entity definition: {}", definition.getEntityDefinition().getName());
            registry.add(definition.getEntityDefinition());

            LOG.info("Registering entity group definitions {}", definition.getEntityGroupDefinitions());
            definition.getEntityGroupDefinitions().forEach(registry::add);

            entityInputTypes.put(definition.getEntityDefinition(), definition.getEntityInputObjectDefinition());

            LOG.info("Registering entity input definition {}", definition.getEntityInputObjectDefinition());
            registry.add(definition.getEntityInputObjectDefinition());

            LOG.info("Registering property group input definitions {}", definition.getEntityGroupDefinitions());
            definition.getEntityGroupInputObjectDefinitions().forEach(registry::add);
        }

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

        return registry;
    }

    private EntityGraphqlDefinition getEntityTypeDefinition(EntityDefinition entityDefinition) {
        Description entityDescription = entityDefinition.getDescription() == null ? null : new Description(entityDefinition.getDescription(), null, false);
        List<FieldDefinition> fieldDefinitions = entityDefinition.getProperties() == null ?
                List.of() :
                entityDefinition.getProperties().stream().map(this::getPropertyFieldDefinition).toList();

        List<ObjectTypeDefinition> propertyGroupDefinitions = entityDefinition.getPropertyGroups() == null ? List.of() :
                entityDefinition.getPropertyGroups().stream().map(group -> getPropertyGroupTypeDefinition(entityDefinition, group)).toList();
        List<InputObjectTypeDefinition> propertyGroupInputDefinitions = entityDefinition.getPropertyGroups() == null ? List.of() :
                entityDefinition.getPropertyGroups().stream().map(group -> getPropertyGroupInputTypeDefinition(entityDefinition, group)).toList();

        List<FieldDefinition> propertyGroupFields = propertyGroupDefinitions.stream().map(groupDef -> {
            TypeName typeName = new TypeName(groupDef.getName());
            Description groupPropertyDescription = groupDef.getDescription();
            return FieldDefinition.newFieldDefinition()
                    .name(groupDef.getAdditionalData().get("propertyGroupName"))
                    .description(groupPropertyDescription)
                    .type(typeName)
                    .build();
        }).toList();

        List<FieldDefinition> allFields = new ArrayList<>();
        allFields.addAll(fieldDefinitions);
        allFields.addAll(propertyGroupFields);

        ObjectTypeDefinition entityObjectDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(entityDefinition.getName())
                .description(entityDescription)
                .fieldDefinitions(allFields)
                .build();

        ArrayList<InputValueDefinition> inputValueDefinitions = new ArrayList<>();
        Set<PropertyDefinition> entityProps = entityDefinition.getProperties();
        inputValueDefinitions.addAll(entityProps == null ? Set.of() : entityProps.stream().map(this::getInputValueDefinition).toList());
        inputValueDefinitions.addAll(propertyGroupInputDefinitions.stream().map(this::getInputValueDefinition).toList());

        InputObjectTypeDefinition entityInputObjectDefinition = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sInput", entityDefinition.getName()))
                .description(entityDescription)
                .additionalData(Map.of(
                        "originalEntity", entityDefinition.getName()
                ))
                .inputValueDefinitions(inputValueDefinitions)
                .build();

        return new EntityGraphqlDefinition(entityObjectDefinition, propertyGroupDefinitions, entityInputObjectDefinition, propertyGroupInputDefinitions);
    }

    private ObjectTypeExtensionDefinition getQueryExtensions(Set<EntityDefinition> entityDefinitions) {
        List<FieldDefinition> fieldDefinitions = entityDefinitions.stream().map(def -> {
            return FieldDefinition.newFieldDefinition()
                    .name(def.getName())
                    .type(new NonNullType(new ListType(new NonNullType(new TypeName(def.getName())))))
                    .build();
        }).toList();

        return ObjectTypeExtensionDefinition.newObjectTypeExtensionDefinition()
                .name("Query")
                .fieldDefinitions(fieldDefinitions)
                .build();
    }

    private InputValueDefinition getInputValueDefinition(PropertyDefinition propertyDefinition) {
        TypeName typeName = switch (propertyDefinition.getType()) {
            case STRING -> new TypeName("String");
            default -> throw new RuntimeException("Unsupported type: " + propertyDefinition.getType());
        };
        Description propertyDescription = propertyDefinition.getDescription() == null ? null : new Description(propertyDefinition.getDescription(), null, false);

        return InputValueDefinition.newInputValueDefinition()
                .name(propertyDefinition.getName())
                .type(typeName)
                .description(propertyDescription)
                .build();
    }

    private InputValueDefinition getInputValueDefinition(InputObjectTypeDefinition inputObjectTypeDefinition) {
        String originalPropertyGroupName = inputObjectTypeDefinition.getAdditionalData().get("propertyGroupName");
        return InputValueDefinition.newInputValueDefinition()
                .name(originalPropertyGroupName == null ? inputObjectTypeDefinition.getName() : originalPropertyGroupName)
                .type(new TypeName(inputObjectTypeDefinition.getName()))
                .description(inputObjectTypeDefinition.getDescription())
                .build();
    }

    private FieldDefinition getPropertyFieldDefinition(PropertyDefinition propertyDefinition) {
        TypeName typeName = switch (propertyDefinition.getType()) {
            case STRING -> new TypeName("String");
            default -> throw new RuntimeException("Unsupported type: " + propertyDefinition.getType());
        };
        Description propertyDescription = propertyDefinition.getDescription() == null ? null : new Description(propertyDefinition.getDescription(), null, false);

        return FieldDefinition.newFieldDefinition()
                .name(propertyDefinition.getName())
                .type(typeName)
                .description(propertyDescription)
                .build();
    }

    private ObjectTypeDefinition getPropertyGroupTypeDefinition(EntityDefinition entityDefinition, PropertyGroupDefinition propertyGroupDefinition) {
        Description groupDescription = propertyGroupDefinition.getDescription() == null ? null : new Description(propertyGroupDefinition.getDescription(), null, false);

        List<FieldDefinition> groupProperties = propertyGroupDefinition.getProperties().stream().map(this::getPropertyFieldDefinition).toList();

        String groupTypeName = String.format("%s_%s", entityDefinition.getName(), propertyGroupDefinition.getName());

        ObjectTypeDefinition groupDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(groupTypeName)
                .description(groupDescription)
                .additionalData(Map.of(
                        "originalEntity", entityDefinition.getName(),
                        "propertyGroupName", propertyGroupDefinition.getName()
                ))
                .fieldDefinitions(groupProperties)
                .build();
        return groupDefinition;
    }

    private InputObjectTypeDefinition getPropertyGroupInputTypeDefinition(EntityDefinition entityDefinition, PropertyGroupDefinition propertyGroupDefinition) {
        Description description = propertyGroupDefinition.getDescription() == null ? null : new Description(propertyGroupDefinition.getDescription(), null, false);

        List<InputValueDefinition> inputValueDefinitions = propertyGroupDefinition.getProperties().stream().map(this::getInputValueDefinition).toList();

        InputObjectTypeDefinition entityInputObjectDefinition = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%s_%sInput", entityDefinition.getName(), propertyGroupDefinition.getName()))
                .description(description)
                .additionalData(Map.of(
                        "originalEntity", entityDefinition.getName(),
                        "propertyGroupName", propertyGroupDefinition.getName()
                ))
                .inputValueDefinitions(inputValueDefinitions)
                .build();

        return entityInputObjectDefinition;
    }

}
