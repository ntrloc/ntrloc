package org.ntrloc.graph.graphql.impl;

import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Description;
import graphql.language.Directive;
import graphql.language.DirectiveDefinition;
import graphql.language.DirectiveLocation;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.StringValue;
import graphql.language.TypeName;
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

    public static final String ENTITY_TYPE_DIRECTIVE_NAME = "entityType";
    public static final String ENTITY_TYPE_NAME_ARGUMENT = "name";

    public GraphqlDefinitions generateTypeDefinitions(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        GraphqlDefinitions retDef = new GraphqlDefinitions();

        createDirectiveDefinitions(retDef);

        createMatcherInputTypes(retDef);

        for (EntityDefinition entityDefinition: entityDefinitions) {
            Set<RelationshipDefinition> relationships = relationshipDefinitions == null ?
                    Set.of() :
                    relationshipDefinitions.stream().filter(reldef -> reldef.getSourceEntity().equals(entityDefinition.getName()) || reldef.getTargetEntity().equals(entityDefinition.getName())).collect(java.util.stream.Collectors.toSet());

            createEntityDefinitions(entityDefinition, relationships, retDef);
        }

        createQueryExtensions(entityDefinitions, retDef);
        createMutation(retDef);

        return retDef;
    }

    private void createDirectiveDefinitions(GraphqlDefinitions definitions) {
        DirectiveDefinition entityTypeDirectiveDefinition = DirectiveDefinition.newDirectiveDefinition()
                .name(ENTITY_TYPE_DIRECTIVE_NAME)
                .directiveLocation(DirectiveLocation.newDirectiveLocation().name("OBJECT").build())
                .inputValueDefinition(new InputValueDefinition("name", new NonNullType(new TypeName("String"))))
                .build();
        definitions.addDirectiveDefinition(entityTypeDirectiveDefinition);
    }

    private void createMatcherInputTypes(GraphqlDefinitions definitions) {
        var allMatcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name("AllMatcherInput")
                .inputValueDefinition(InputValueDefinition.newInputValueDefinition().name("matchAll").type(new TypeName("Boolean")).defaultValue(BooleanValue.of(true)).build())
                .build();
        definitions.addInputObjectTypeDefinition(allMatcherInput);

        var propertyMatcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name("PropertyMatcherInput")
                .inputValueDefinition(InputValueDefinition.newInputValueDefinition().name("name").type(new TypeName("String")).build())
                .build();
        definitions.addInputObjectTypeDefinition(propertyMatcherInput);

        var propertyValueMatcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name("PropertyValueMatcherInput")
                .inputValueDefinitions(List.of(
                        InputValueDefinition.newInputValueDefinition().name("name").type(new TypeName("String")).build(),
                        InputValueDefinition.newInputValueDefinition().name("value").type(new TypeName("String")).build()
                ))
                .build();
        definitions.addInputObjectTypeDefinition(propertyValueMatcherInput);

        InputValueDefinition clauseValue = InputValueDefinition.newInputValueDefinition().name("clauses").type(new ListType(new NonNullType(new TypeName("MatcherInput")))).build();

        var andMatcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name("AndMatcherInput")
                .inputValueDefinition(clauseValue)
                .build();
        definitions.addInputObjectTypeDefinition(andMatcherInput);

        var orMatcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name("OrMatcherInput")
                .inputValueDefinition(clauseValue)
                .build();
        definitions.addInputObjectTypeDefinition(orMatcherInput);

        var idMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("String")).name("id").build();
        var refMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("String")).name("ref").build();
        var allMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("AllMatcherInput")).name("all").build();
        var propertyMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("PropertyMatcherInput")).name("property").build();
        var propertyValueMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("PropertyValueMatcherInput")).name("propertyValue").build();
        var andValueMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("AndMatcherInput")).name("and").build();
        var orValueMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("OrMatcherInput")).name("or").build();

        var matcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name("MatcherInput")
                .directive(Directive.newDirective().name("oneOf").build())
                .inputValueDefinitions(List.of(idMatcherValue, refMatcherValue, allMatcherValue, propertyMatcherValue, propertyValueMatcherValue, andValueMatcherValue, orValueMatcherValue))
                .build();
        definitions.addInputObjectTypeDefinition(matcherInput);

    }

    private void createEntityDefinitions(EntityDefinition entityDefinition, Set<RelationshipDefinition> relationships, GraphqlDefinitions retDef) {
        // create input definitions
        createEntityInputDefinitions(entityDefinition, relationships, retDef);

        // crate output definitions
        createEntityOutputDefinitions(entityDefinition, relationships, retDef);
    }

    private void createEntityInputDefinitions(EntityDefinition entityDefinition, Set<RelationshipDefinition> relationships, GraphqlDefinitions retDef) {

        // entity properties input -- used in create and update
        List<InputValueDefinition> entityPropertyInputDefinitions = entityDefinition.getProperties() == null ?
                List.of() :
                entityDefinition.getProperties().stream().map(this::getPropertyInputValueDefinition).toList();
        InputObjectTypeDefinition entityPropertiesInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sPropertiesInput", entityDefinition.getName()))
                .inputValueDefinitions(entityPropertyInputDefinitions)
                .build();
        InputValueDefinition entityPropertiesInputValue = InputValueDefinition.newInputValueDefinition()
                .name("properties")
                .type(new TypeName(entityPropertiesInputType.getName()))
                .build();
        retDef.addInputObjectTypeDefinition(entityPropertiesInputType);

        // entity create
        InputObjectTypeDefinition entityCreateInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sCreateInput", entityDefinition.getName()))
                .inputValueDefinitions(List.of(entityPropertiesInputValue))
                .build();
        retDef.addInputObjectTypeDefinition(entityCreateInputType);
        InputValueDefinition createValue = InputValueDefinition.newInputValueDefinition()
                .name("create")
                .type(new TypeName(entityCreateInputType.getName()))
                .build();

        // entity update
        InputObjectTypeDefinition entityUpdateInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sUpdateInput", entityDefinition.getName()))
                .inputValueDefinitions(List.of(entityPropertiesInputValue))
                .build();
        retDef.addInputObjectTypeDefinition(entityUpdateInputType);
        InputValueDefinition updateValue = InputValueDefinition.newInputValueDefinition()
                .name("update")
                .type(new TypeName(entityUpdateInputType.getName()))
                .build();

        // entity delete
        InputObjectTypeDefinition entityDeleteInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sDeleteInput", entityDefinition.getName()))
                .inputValueDefinition(InputValueDefinition.newInputValueDefinition().name("id").type(new TypeName("String")).build())
                .build();
        retDef.addInputObjectTypeDefinition(entityDeleteInputType);
        InputValueDefinition deleteValue = InputValueDefinition.newInputValueDefinition()
                .name("delete")
                .type(new TypeName(entityDeleteInputType.getName()))
                .build();

        // entity input object (oneOf entity create/update/delete)
        InputObjectTypeDefinition entityInputObjectDefinition = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sInput", entityDefinition.getName()))
                .directive(Directive.newDirective().name("oneOf").build())
                .inputValueDefinitions(List.of(createValue, updateValue, deleteValue))
                .build();
        retDef.addInputObjectTypeDefinition(entityInputObjectDefinition);

        // entity links object
        // for each link type,
        //      entity link create
        //      entity link update
        //      entity link delete
        //      entity link modification (oneOf create/update/delete)
    }

    private void createEntityOutputDefinitions(EntityDefinition entityDefinition, Set<RelationshipDefinition> relationships, GraphqlDefinitions retDef) {

        // create an object type for each property group
        List<ObjectTypeDefinition> propertyGroupDefinitions = entityDefinition.getPropertyGroups() == null ? List.of() :
                entityDefinition.getPropertyGroups().stream().map(group -> getEntityPropertyGroupTypeDefinition(entityDefinition, group)).toList();
        for (ObjectTypeDefinition groupDef: propertyGroupDefinitions) {
            retDef.addObjectTypeDefinition(groupDef);
        }

        // create the field definitions for the entity properties
        List<FieldDefinition> fieldDefinitions = entityDefinition.getProperties() == null ?
                List.of() :
                entityDefinition.getProperties().stream().map(this::getPropertyFieldDefinition).toList();

        // create the field definitions for the entity property groups
        List<FieldDefinition> propertyGroupFields = propertyGroupDefinitions.stream().map(groupDef -> {
            TypeName typeName = new TypeName(groupDef.getName());
            Description groupPropertyDescription = groupDef.getDescription();
            return FieldDefinition.newFieldDefinition()
                    .name(groupDef.getAdditionalData().get("propertyGroupName").toLowerCase())
                    .description(groupPropertyDescription)
                    .type(typeName)
                    .build();
        }).toList();

        // create an object type for the entity properties
        List<FieldDefinition> allFields = new ArrayList<>();
        allFields.addAll(fieldDefinitions);
        allFields.addAll(propertyGroupFields);
        ObjectTypeDefinition entityPropertiesTypeDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                        .name(String.format("%sProperties", entityDefinition.getName()))
                        .fieldDefinitions(allFields)
                        .build();
        retDef.addObjectTypeDefinition(entityPropertiesTypeDefinition);


        // create the relationship types for this entity and record the "friendly" name and GraphQL object type.
        Map<String, String> relationshipAliasToObjectTypeMap = new HashMap<>();

        for (RelationshipDefinition relationshipDefinition : relationships) {
            ObjectTypeDefinition relationshipPropertiesType = addRelationshipPropertiesType(relationshipDefinition, retDef);

            Description relationshipDescription = relationshipDefinition.getDescription() == null ? null : new Description(relationshipDefinition.getDescription(), null, false);

            FieldDefinition relationshipIdField = FieldDefinition.newFieldDefinition()
                    .name("id")
                    .type(new TypeName("String"))
                    .build();
            FieldDefinition relationshipLabelField = FieldDefinition.newFieldDefinition()
                    .name("label")
                    .type(new TypeName("String"))
                    .build();
            FieldDefinition relationshipPropertiesField = FieldDefinition.newFieldDefinition()
                    .name("properties")
                    .type(new TypeName(relationshipPropertiesType.getName()))
                    .build();

            String relationshipBaseTypeName = getRelationshipBaseTypeName(relationshipDefinition);

            String relationshipAlias;
            String relationshipTypeName;
            FieldDefinition sourceOrTargetField;
            if (relationshipDefinition.getSourceEntity().equals(entityDefinition.getName())) {
                relationshipAlias = relationshipDefinition.getSourceLabel();
                relationshipTypeName = String.format("%sTarget",relationshipBaseTypeName);
                sourceOrTargetField = FieldDefinition.newFieldDefinition()
                        .name("target")
                        .type(new TypeName(relationshipDefinition.getTargetEntity()))
                        .build();
            } else {
                relationshipAlias = relationshipDefinition.getTargetLabel();
                relationshipTypeName = String.format("%sSource",relationshipBaseTypeName);
                sourceOrTargetField = FieldDefinition.newFieldDefinition()
                        .name("source")
                        .type(new TypeName(relationshipDefinition.getSourceEntity()))
                        .build();
            }
            relationshipAliasToObjectTypeMap.put(relationshipAlias, relationshipTypeName);

            // create the relationship base type
            ObjectTypeDefinition relationshipObjectDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(relationshipTypeName)
                    .description(relationshipDescription)
                    .fieldDefinitions(List.of(relationshipIdField, relationshipLabelField, relationshipPropertiesField, sourceOrTargetField))
                    .build();
            retDef.addObjectTypeDefinition(relationshipObjectDefinition);
        }

        // entity links object (contains a field for each link type)
        List<FieldDefinition> linkFieldDefinitions = relationshipAliasToObjectTypeMap.entrySet().stream()
                .map(entry -> {
                    FieldDefinition def = FieldDefinition.newFieldDefinition()
                            .name(entry.getKey())
                            .type(new TypeName(entry.getValue()))
                            .build();
                    return def;
                }).toList();
        ObjectTypeDefinition entityLinksType = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(String.format("%sLinks", entityDefinition.getName()))
                .fieldDefinitions(linkFieldDefinitions)
                .build();
        retDef.addObjectTypeDefinition(entityLinksType);

        // finally, create the entity object type
        Description entityDescription = entityDefinition.getDescription() == null ? null : new Description(entityDefinition.getDescription(), null, false);
        Directive entityTypeDirective = Directive.newDirective()
                .name(ENTITY_TYPE_DIRECTIVE_NAME)
                .argument(Argument.newArgument().name(ENTITY_TYPE_NAME_ARGUMENT).value(StringValue.of(entityDefinition.getName())).build())
                .build();
        FieldDefinition idField = FieldDefinition.newFieldDefinition()
                .name("id")
                .type(new TypeName("String"))
                .build();
        FieldDefinition labelField = FieldDefinition.newFieldDefinition()
                .name("label")
                .type(new TypeName("String"))
                .build();
        FieldDefinition entityPropertyField = FieldDefinition.newFieldDefinition()
                .name("properties")
                .type(new TypeName(entityPropertiesTypeDefinition.getName()))
                .build();
        FieldDefinition entityLinksField = FieldDefinition.newFieldDefinition()
                .name("links")
                .type(new TypeName(entityLinksType.getName()))
                .build();
        ObjectTypeDefinition entityObjectDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(entityDefinition.getName())
                .description(entityDescription)
                .fieldDefinitions(List.of(idField, labelField, entityPropertyField, entityLinksField))
                .directive(entityTypeDirective)
                .build();
        retDef.addEntityObjectTypeDefinition(entityObjectDefinition);
    }

    private FieldDefinition getPropertyFieldDefinition(PropertyDefinition propertyDefinition) {
        TypeName typeName = switch (propertyDefinition.getType()) {
            case STRING -> new TypeName("String");
            case INT -> new TypeName("Int");
            default -> throw new RuntimeException("Unsupported type: " + propertyDefinition.getType());
        };
        Description propertyDescription = propertyDefinition.getDescription() == null ?
                null :
                new Description(propertyDefinition.getDescription(), null, false);

        return FieldDefinition.newFieldDefinition()
                .name(propertyDefinition.getName())
                .type(typeName)
                .description(propertyDescription)
                .build();
    }

    private InputValueDefinition getPropertyInputValueDefinition(PropertyDefinition propertyDefinition) {
        TypeName typeName = switch (propertyDefinition.getType()) {
            case STRING -> new TypeName("String");
            case INT -> new TypeName("Int");
            default -> throw new RuntimeException("Unsupported type: " + propertyDefinition.getType());
        };
        Description propertyDescription = propertyDefinition.getDescription() == null ?
                null :
                new Description(propertyDefinition.getDescription(), null, false);

        return InputValueDefinition.newInputValueDefinition()
                .name(propertyDefinition.getName())
                .type(typeName)
                .description(propertyDescription)
                .build();
    }

    private ObjectTypeDefinition getEntityPropertyGroupTypeDefinition(EntityDefinition entityDefinition, PropertyGroupDefinition propertyGroupDefinition) {
        Description groupDescription = propertyGroupDefinition.getDescription() == null ? null : new Description(propertyGroupDefinition.getDescription(), null, false);
        List<FieldDefinition> groupProperties = propertyGroupDefinition.getProperties().stream().map(this::getPropertyFieldDefinition).toList();
        String groupTypeName = String.format("%s%s", entityDefinition.getName(), propertyGroupDefinition.getName());
        return ObjectTypeDefinition.newObjectTypeDefinition()
                .name(groupTypeName)
                .description(groupDescription)
                .additionalData(Map.of(
                        "originalEntity", entityDefinition.getName(),
                        "propertyGroupName", propertyGroupDefinition.getName()
                ))
                .fieldDefinitions(groupProperties)
                .build();
    }

    private String getRelationshipBaseTypeName(RelationshipDefinition relationshipDefinition) {
        return String.format("%s%s%s",
                relationshipDefinition.getSourceEntity(),
                relationshipDefinition.getName(),
                relationshipDefinition.getTargetEntity());
    }

    /*
     * Returns the properties object type for the given relationship if it hasn't already been registered,
     * creating it first if necessary.
     */
    private ObjectTypeDefinition addRelationshipPropertiesType(RelationshipDefinition relationshipDefinition, GraphqlDefinitions retDef) {
        String relationshipBaseTypeName = getRelationshipBaseTypeName(relationshipDefinition);
        String relationshipPropertiesTypeName = String.format("%sProperties", relationshipBaseTypeName);

        if (!retDef.containsObjectTypeDefinition(relationshipBaseTypeName)) {
            // create an object type for each property group
            List<ObjectTypeDefinition> relationshipPropertyGroupDefinitions = relationshipDefinition.getPropertyGroups() == null ? List.of() :
                    relationshipDefinition.getPropertyGroups().stream().map(group -> getRelationshipPropertyGroupTypeDefinition(relationshipDefinition, group)).toList();
            for (ObjectTypeDefinition groupDef: relationshipPropertyGroupDefinitions) {
                retDef.addObjectTypeDefinition(groupDef);
            }

            // create the field definitions for the relationship properties
            List<FieldDefinition> relationshipFieldDefinitions = relationshipDefinition.getProperties() == null ?
                    List.of() :
                    relationshipDefinition.getProperties().stream().map(this::getPropertyFieldDefinition).toList();

            List<FieldDefinition> relationshipPropertyGroupFields = relationshipPropertyGroupDefinitions.stream().map(groupDef -> {
                TypeName typeName = new TypeName(groupDef.getName());
                Description groupPropertyDescription = groupDef.getDescription();
                return FieldDefinition.newFieldDefinition()
                        .name(groupDef.getAdditionalData().get("propertyGroupName").toLowerCase())
                        .description(groupPropertyDescription)
                        .type(typeName)
                        .build();
            }).toList();

            // create an object type for the relationship properties
            List<FieldDefinition> allRelationshipFields = new ArrayList<>();
            allRelationshipFields.addAll(relationshipFieldDefinitions);
            allRelationshipFields.addAll(relationshipPropertyGroupFields);
            ObjectTypeDefinition relationshipPropertiesTypeDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                    .name(relationshipPropertiesTypeName)
                    .fieldDefinitions(allRelationshipFields)
                    .build();
            retDef.addObjectTypeDefinition(relationshipPropertiesTypeDefinition);
            return relationshipPropertiesTypeDefinition;
        } else {
            return retDef.getObjectTypeDefinition(relationshipPropertiesTypeName);
        }
    }

    private ObjectTypeDefinition getRelationshipPropertyGroupTypeDefinition(RelationshipDefinition relationshipDefinition, PropertyGroupDefinition propertyGroupDefinition) {
        Description groupDescription = propertyGroupDefinition.getDescription() == null ? null : new Description(propertyGroupDefinition.getDescription(), null, false);
        List<FieldDefinition> groupProperties = propertyGroupDefinition.getProperties().stream().map(this::getPropertyFieldDefinition).toList();
        String groupTypeName = String.format("%s%s", relationshipDefinition.getName(), propertyGroupDefinition.getName());
        return ObjectTypeDefinition.newObjectTypeDefinition()
                .name(groupTypeName)
                .description(groupDescription)
                .additionalData(Map.of(
                        "originalRelationship", relationshipDefinition.getName(),
                        "propertyGroupName", propertyGroupDefinition.getName()
                ))
                .fieldDefinitions(groupProperties)
                .build();
    }

    private void createQueryExtensions(Set<EntityDefinition> entityDefinitions, GraphqlDefinitions definitions) {
        List<FieldDefinition> fieldDefinitions = entityDefinitions.stream().map(def -> {
            return FieldDefinition.newFieldDefinition()
                    .name(def.getName())
                    .type(new NonNullType(new ListType(new NonNullType(new TypeName(def.getName())))))
                    .build();
        }).toList();

        if (!fieldDefinitions.isEmpty()) {
            ObjectTypeExtensionDefinition def = ObjectTypeExtensionDefinition.newObjectTypeExtensionDefinition()
                    .name("Query")
                    .fieldDefinitions(fieldDefinitions)
                    .build();
            definitions.addObjectTypeExtensionDefinition(def);
        }
    }

    private void createMutation(GraphqlDefinitions definitions) {

    }

}
