package org.ntrloc.graph.graphql.impl;

import graphql.language.Argument;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GraphQLSchemaGeneratorImpl implements GraphQLSchemaGenerator {

    private static final Logger LOG = LogManager.getLogger(GraphQLSchemaGeneratorImpl.class);

    public static final String ENTITY_TYPE_DIRECTIVE_NAME = "entityType";
    public static final String ENTITY_TYPE_NAME_ARGUMENT = "name";

    @Override
    public GraphqlDefinitions generateTypeDefinitions(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        GraphqlDefinitions retDef = new GraphqlDefinitions();
        retDef = retDef.directiveDefinitions(getDirectiveDefinitions());

        for (EntityDefinition entityDefinition: entityDefinitions) {

            Set<RelationshipDefinition> relationships = relationshipDefinitions == null ?
                    Set.of() :
                    relationshipDefinitions.stream().filter(reldef -> reldef.getSourceEntity().equals(entityDefinition.getName()) || reldef.getTargetEntity().equals(entityDefinition.getName())).collect(Collectors.toSet());

            GraphqlDefinitions definition = getGraphqlDefinitions(entityDefinition, relationships);
            retDef = retDef.merge(definition);
        }

        return retDef;
    }

    private List<DirectiveDefinition> getDirectiveDefinitions() {
        List<DirectiveDefinition> definitions = new ArrayList<>();

        DirectiveDefinition entityTypeDirectiveDefinition = DirectiveDefinition.newDirectiveDefinition()
                .name(ENTITY_TYPE_DIRECTIVE_NAME)
                .directiveLocation(DirectiveLocation.newDirectiveLocation().name("OBJECT").build())
                .inputValueDefinition(new InputValueDefinition("name", new NonNullType(new TypeName("String"))))
                .build();
        definitions.add(entityTypeDirectiveDefinition);

        return definitions;

    }

    private GraphqlDefinitions getGraphqlDefinitions(EntityDefinition entityDefinition, Set<RelationshipDefinition> relationshipDefinitions) {
        List<ObjectTypeDefinition> objectTypeDefinitions = new ArrayList<>();

        Description entityDescription = entityDefinition.getDescription() == null ? null : new Description(entityDefinition.getDescription(), null, false);
        List<FieldDefinition> fieldDefinitions = entityDefinition.getProperties() == null ?
                List.of() :
                entityDefinition.getProperties().stream().map(this::getPropertyFieldDefinition).toList();

        List<ObjectTypeDefinition> propertyGroupDefinitions = entityDefinition.getPropertyGroups() == null ? List.of() :
                entityDefinition.getPropertyGroups().stream().map(group -> getPropertyGroupTypeDefinition(entityDefinition, group)).toList();

        List<FieldDefinition> propertyGroupFields = propertyGroupDefinitions.stream().map(groupDef -> {
            TypeName typeName = new TypeName(groupDef.getName());
            Description groupPropertyDescription = groupDef.getDescription();
            return FieldDefinition.newFieldDefinition()
                    .name(groupDef.getAdditionalData().get("propertyGroupName").toLowerCase())
                    .description(groupPropertyDescription)
                    .type(typeName)
                    .build();
        }).toList();

        objectTypeDefinitions.addAll(propertyGroupDefinitions);

        List<FieldDefinition> allFields = new ArrayList<>();
        allFields.addAll(fieldDefinitions);
        allFields.addAll(propertyGroupFields);

        Directive entityTypeDirective = Directive.newDirective()
                .name(ENTITY_TYPE_DIRECTIVE_NAME)
                .argument(Argument.newArgument().name(ENTITY_TYPE_NAME_ARGUMENT).value(StringValue.of(entityDefinition.getName())).build())
                .build();

        ObjectTypeDefinition entityObjectDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(entityDefinition.getName())
                .description(entityDescription)
                .fieldDefinitions(allFields)
                .directive(entityTypeDirective)
                .build();
        objectTypeDefinitions.add(entityObjectDefinition);


        // create types and fields for the relationships
        for (RelationshipDefinition rel: relationshipDefinitions) {
            var relationshipObjectDefinition = getRelationshipObjectDefinition(entityDefinition, rel);
            objectTypeDefinitions.add(relationshipObjectDefinition);
        }

        ArrayList<InputValueDefinition> inputValueDefinitions = new ArrayList<>();
        Set<PropertyDefinition> entityProps = entityDefinition.getProperties();
        inputValueDefinitions.addAll(entityProps == null ? Set.of() : entityProps.stream().map(this::getInputValueDefinition).toList());

        Set<PropertyGroupDefinition> propertyGroups = entityDefinition.getPropertyGroups();
        inputValueDefinitions.addAll(propertyGroups == null ? Set.of() : propertyGroups.stream().flatMap(group -> group.getProperties().stream()).map(this::getInputValueDefinition).toList());

        InputObjectTypeDefinition entityInputObjectDefinition = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sInput", entityDefinition.getName()))
                .description(entityDescription)
                .additionalData(Map.of(
                        "originalEntity", entityDefinition.getName()
                ))
                .inputValueDefinitions(inputValueDefinitions)
                .build();


        return new GraphqlDefinitions().objectTypes(objectTypeDefinitions).inputObjectTypes(List.of(entityInputObjectDefinition));
    }

    private ObjectTypeDefinition getRelationshipObjectDefinition(EntityDefinition entityDefinition, RelationshipDefinition relationshipDefinition) {

        var inboundRelationship = relationshipDefinition.getTargetEntity().equals(entityDefinition.getName());

        List<FieldDefinition> relationshipFields = new ArrayList<>();

        FieldDefinition entityRelationshipPropertiesField = FieldDefinition.newFieldDefinition()
                .name("properties")
                .type(new NonNullType(new ListType(new NonNullType(new TypeName("String"))))) // TODO: this should be the actual relationship properties type
                .build();
        relationshipFields.add(entityRelationshipPropertiesField);

        String relationshipTypeName;
        if (inboundRelationship) {
            relationshipTypeName = String.format("%s%s%s", relationshipDefinition.getTargetEntity(), relationshipDefinition.getTargetLabel(), relationshipDefinition.getSourceEntity());
            FieldDefinition entityRelationshipFromField = FieldDefinition.newFieldDefinition()
                    .name("from")
                    .type(new NonNullType(new TypeName(relationshipDefinition.getSourceEntity())))
                    .build();
            relationshipFields.add(entityRelationshipFromField);
        } else {
            relationshipTypeName = String.format("%s%s%s", relationshipDefinition.getSourceEntity(), relationshipDefinition.getSourceLabel(), relationshipDefinition.getTargetEntity());
            FieldDefinition entityRelationshipToField = FieldDefinition.newFieldDefinition()
                    .name("to")
                    .type(new NonNullType(new TypeName(relationshipDefinition.getTargetEntity())))
                    .build();
            relationshipFields.add(entityRelationshipToField);
        }

        ObjectTypeDefinition relationshipObjectDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(relationshipTypeName)
                .description(relationshipDefinition.getDescription() == null ? null : new Description(relationshipDefinition.getDescription(), null, false))
                .fieldDefinitions(relationshipFields)
                //.directive(entityTypeDirective)
                .build();

        /* we also need to create the property object for the relationship
         * note that the type is defined during construction of the outbound relationship type
         * and is referenced again by the inbound relationship type
         * e.g. Photographer, Photo, and Photographer->CREATED->Photo will yield:
         * object type Photo
         *      field properties: PhotoProperties
         * object type PhotoProperties
         *      (various fields)
         * object type PhotoLinks
         *      field creator: PhotoCreatorPhotographer
         * object field PhotoCreatorPhotographer
         *      field properties: PhotographerCreatedPhotoProperties
         *      field from: Photographer
         * object type Photographer
         *      field properties: PhotographerProperties
         * object type ProtographerProperties
         *      (various fields)
         * object type PhotographerLinks
         *      field created: PhotographerCreatedPhoto
         * object type PhotographerCreatedPhoto
         *      field properties: PhotographerCreatedPhotoProperties
         *      field to: Photo
         * object type PhotographerCreatedPhotoProperties
         *
         * note: we're going to need input types for most of this stuff too
         * note: we also need to define the create/update/delete input types for entities
         * note: we also need to define the create/update/delete input types for entity relationships
         */

        return relationshipObjectDefinition;

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
            case INT -> new TypeName("Int");
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
            case INT -> new TypeName("Int");
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

        String groupTypeName = String.format("%s%s", entityDefinition.getName(), propertyGroupDefinition.getName());

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

}
