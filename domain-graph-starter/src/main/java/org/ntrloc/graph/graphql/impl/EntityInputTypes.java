package org.ntrloc.graph.graphql.impl;

import graphql.language.Description;
import graphql.language.Directive;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class EntityInputTypes {

    private EntityDefinition entityDefinition;

    /** An input type for entity properties */
    private InputObjectTypeDefinition entityPropertiesInputType;

    /** An input type for entity creation */
    private InputObjectTypeDefinition entityCreateInputType;

    /** An input type for entity update */
    private InputObjectTypeDefinition entityUpdateInputType;

    /** An input type for entity deletion */
    private InputObjectTypeDefinition entityDeleteInputType;

    /** An input type that allows any operation (create, update, or delete) */
    private InputObjectTypeDefinition entityAnyOperationInputType;

    /** An input type that is used to create links during entity creation. */
    private InputObjectTypeDefinition linkCreateInputType;

    /** An input type that is used to update links during entity update. */
    private InputObjectTypeDefinition linkUpdateInputType;

    private List<RelationshipInputTypes> outboundRelationshipInputTypes;

    public EntityInputTypes(EntityDefinition entityDefinition, Set<RelationshipDefinition> relationships) {
        this.entityDefinition = entityDefinition;
        parseEntityInputTypes(entityDefinition, relationships);
    }

    public EntityDefinition getEntityDefinition() {
        return entityDefinition;
    }

    public InputObjectTypeDefinition getEntityPropertiesInputType() {
        return entityPropertiesInputType;
    }

    public InputObjectTypeDefinition getEntityCreateInputType() {
        return entityCreateInputType;
    }

    public InputObjectTypeDefinition getEntityUpdateInputType() {
        return entityUpdateInputType;
    }

    public InputObjectTypeDefinition getEntityDeleteInputType() {
        return entityDeleteInputType;
    }

    public InputObjectTypeDefinition getEntityAnyOperationInputType() {
        return entityAnyOperationInputType;
    }

    public List<RelationshipInputTypes> getOutboundRelationshipInputTypes() {
        return outboundRelationshipInputTypes;
    }

    public List<InputObjectTypeDefinition> getEntityInputTypes() {
        return Stream.of(entityPropertiesInputType, entityCreateInputType, entityUpdateInputType, entityDeleteInputType, entityAnyOperationInputType,
                        linkCreateInputType, linkUpdateInputType)
                .filter(Objects::nonNull).toList();
    }

    public List<InputObjectTypeDefinition> getRelationshipInputTypes() {
        return outboundRelationshipInputTypes.stream().map(RelationshipInputTypes::getInputTypes).flatMap(List::stream).toList();
    }

    private void parseEntityInputTypes(EntityDefinition entityDefinition, Set<RelationshipDefinition> relationships) {
        // entity properties input -- used in create and update
        List<InputValueDefinition> entityPropertyInputDefinitions = entityDefinition.getProperties() == null ?
                List.of() :
                entityDefinition.getProperties().stream().map(this::getPropertyInputValueDefinition).toList();
        entityPropertiesInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sPropertiesInput", entityDefinition.getName()))
                .inputValueDefinitions(entityPropertyInputDefinitions)
                .build();

        InputValueDefinition entityPropertiesInputValue = InputValueDefinition.newInputValueDefinition()
                .name("properties")
                .type(new TypeName(entityPropertiesInputType.getName()))
                .build();

        InputValueDefinition referenceValueDefinition = InputValueDefinition.newInputValueDefinition()
                .name("ref")
                .type(new TypeName("String"))
                .build();

        // entity create
        entityCreateInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sCreateInput", entityDefinition.getName()))
                .inputValueDefinitions(List.of(referenceValueDefinition, entityPropertiesInputValue))
                .build();

        InputValueDefinition createValue = InputValueDefinition.newInputValueDefinition()
                .name("create")
                .type(new TypeName(entityCreateInputType.getName()))
                .build();

        // entity update
        entityUpdateInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sUpdateInput", entityDefinition.getName()))
                .inputValueDefinitions(List.of(referenceValueDefinition, entityPropertiesInputValue))
                .build();

        InputValueDefinition updateValue = InputValueDefinition.newInputValueDefinition()
                .name("update")
                .type(new TypeName(entityUpdateInputType.getName()))
                .build();

        // entity delete
        entityDeleteInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sDeleteInput", entityDefinition.getName()))
                .inputValueDefinition(InputValueDefinition.newInputValueDefinition().name("id").type(new TypeName("String")).build())
                .build();

        InputValueDefinition deleteValue = InputValueDefinition.newInputValueDefinition()
                .name("delete")
                .type(new TypeName(entityDeleteInputType.getName()))
                .build();

        // entity input object (oneOf entity create/update/delete)
        entityAnyOperationInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sInput", entityDefinition.getName()))
                .directive(Directive.newDirective().name("oneOf").build())
                .inputValueDefinitions(List.of(createValue, updateValue, deleteValue))
                .build();

        List<RelationshipDefinition> outboundRelationships = relationships.stream().filter(rel -> rel.getSourceEntity().equals(entityDefinition.getName())).toList();
        outboundRelationshipInputTypes = outboundRelationships.stream().map(RelationshipInputTypes::new).toList();

        // define the object type that governs what link info can be supplied during entity creation
        List<Tuple<String, InputObjectTypeDefinition>> linkCreateTypes = outboundRelationshipInputTypes.stream().map(RelationshipInputTypes::getLinkCreateInputType).toList();
        if (!linkCreateTypes.isEmpty()) {
            List<InputValueDefinition> linkInputValues = new ArrayList<>();
            for (Tuple<String, InputObjectTypeDefinition> linkInput: linkCreateTypes) {
                String name = linkInput.first();
                InputObjectTypeDefinition inputObjectTypeDefinition = linkInput.second();
                linkInputValues.add(InputValueDefinition.newInputValueDefinition()
                        .name(name)
                        .type(new ListType(new NonNullType(new TypeName(inputObjectTypeDefinition.getName()))))
                        .build());
            }
            linkCreateInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                    .name(String.format("%sCreateLinksInput", entityDefinition.getName()))
                    .inputValueDefinitions(linkInputValues)
                    .build();
        }

        // define the object type that governs what link info can be supplied during entity update
        List<Tuple<String, InputObjectTypeDefinition>> linkUpdateTypes = outboundRelationshipInputTypes.stream().map(RelationshipInputTypes::getLinkModificationInputType).toList();
        if (!linkUpdateTypes.isEmpty()) {
            List<InputValueDefinition> linkUpdateValues = new ArrayList<>();
            for (Tuple<String, InputObjectTypeDefinition> linkInput: linkUpdateTypes) {
                String name = linkInput.first();
                InputObjectTypeDefinition inputObjectTypeDefinition = linkInput.second();
                linkUpdateValues.add(InputValueDefinition.newInputValueDefinition()
                        .name(name)
                        .type(new ListType(new NonNullType(new TypeName(inputObjectTypeDefinition.getName()))))
                        .build());
            }
            linkUpdateInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                    .name(String.format("%sCreateLinksUpdate", entityDefinition.getName()))
                    .inputValueDefinitions(linkUpdateValues)
                    .build();
        }

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

}
