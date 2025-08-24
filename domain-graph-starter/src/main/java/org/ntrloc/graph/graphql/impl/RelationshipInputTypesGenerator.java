package org.ntrloc.graph.graphql.impl;

import graphql.language.Description;
import graphql.language.Directive;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;

import java.util.List;

/**
 * Used to create, but NOT REGISTER, GraphQL input types for a relationship.
 */
class RelationshipInputTypesGenerator {

    private RelationshipDefinition relationshipDefinition;

    /** The input object type for relationship properties */
    private InputObjectTypeDefinition propertyTypeInputDefinition;

    /** The input object type for the creation of a relationship. */
    private InputObjectTypeDefinition linkCreateInputType;

    /** The input object type for updating a relationship. */
    private InputObjectTypeDefinition linkUpdateInputType;

    /** The input object type for deleting a relationship. */
    private InputObjectTypeDefinition linkDeleteInputType;

    /** The input object type to express any kind of relationship change (create, update, or delete) */
    private InputObjectTypeDefinition linkAnyModificationInputType;

    public RelationshipInputTypesGenerator(RelationshipDefinition relationshipDefinition) {
        this.relationshipDefinition = relationshipDefinition;
        calculateInputTypes(relationshipDefinition);
    }

    public RelationshipDefinition getRelationshipDefinition() {
        return relationshipDefinition;
    }

    public Tuple<String, InputObjectTypeDefinition> getLinkCreateInputType() {
        return Tuple.of(relationshipDefinition.getSourceLabel(), linkCreateInputType);
    }

    public Tuple<String, InputObjectTypeDefinition> getLinkModificationInputType() {
        return Tuple.of(relationshipDefinition.getSourceLabel(), linkAnyModificationInputType);
    }

    public List<InputObjectTypeDefinition> getInputTypes() {
        return List.of(propertyTypeInputDefinition, linkCreateInputType, linkUpdateInputType, linkDeleteInputType, linkAnyModificationInputType);
    }

    private void calculateInputTypes(RelationshipDefinition relationshipDefinition) {
        // each relationship type gets a properties object input type
        propertyTypeInputDefinition = getPropertiesInputType(relationshipDefinition);

        InputValueDefinition matchDefinition = InputValueDefinition.newInputValueDefinition()
                .type(new TypeName("MatcherInput"))
                .name("target")
                .build();
        InputValueDefinition propertiesDefinition = InputValueDefinition.newInputValueDefinition()
                .name("properties")
                .type(new TypeName(propertyTypeInputDefinition.getName()))
                .build();

        // link create input
        String linkCreateType = String.format("%s%s%sLinkCreateInput", relationshipDefinition.getSourceEntity(), relationshipDefinition.getSourceLabel(), relationshipDefinition.getTargetEntity());
        linkCreateInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(linkCreateType)
                .inputValueDefinitions(List.of(matchDefinition, propertiesDefinition))
                .build();

        // link update input
        String linkUpdateType = String.format("%s%s%sLinkUpdateInput", relationshipDefinition.getSourceEntity(), relationshipDefinition.getSourceLabel(), relationshipDefinition.getTargetEntity());
        linkUpdateInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(linkUpdateType)
                .inputValueDefinitions(List.of(matchDefinition, propertiesDefinition))
                .build();

        // link delete input
        String linkDeleteType = String.format("%s%s%sLinkDeleteInput", relationshipDefinition.getSourceEntity(), relationshipDefinition.getSourceLabel(), relationshipDefinition.getTargetEntity());
        linkDeleteInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(linkDeleteType)
                .inputValueDefinitions(List.of(matchDefinition))
                .build();

        // link modification input (oneOf create, update, or delete)
        String linkModificationType = String.format("%s%s%sLinkModificationInput", relationshipDefinition.getSourceEntity(), relationshipDefinition.getSourceLabel(), relationshipDefinition.getTargetEntity());
        linkAnyModificationInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(linkModificationType)
                .directive(Directive.newDirective().name("oneOf").build())
                .inputValueDefinitions(List.of(
                        InputValueDefinition.newInputValueDefinition().name("create").type(new TypeName(linkCreateType)).build(),
                        InputValueDefinition.newInputValueDefinition().name("update").type(new TypeName(linkUpdateType)).build(),
                        InputValueDefinition.newInputValueDefinition().name("delete").type(new TypeName(linkDeleteType)).build()
                ))
                .build();

    }

    private InputObjectTypeDefinition getPropertiesInputType(RelationshipDefinition relationshipDefinition) {
        String linkPropertiesTypeName = String.format("%s%s%sLinkProperties", relationshipDefinition.getSourceEntity(), relationshipDefinition.getSourceLabel(), relationshipDefinition.getTargetEntity());
        List<InputValueDefinition> linkPropertyInputDefinitions = relationshipDefinition.getProperties() == null ?
                List.of() :
                relationshipDefinition.getProperties().stream().map(this::getPropertyInputValueDefinition).toList();
        return InputObjectTypeDefinition.newInputObjectDefinition()
                .name(linkPropertiesTypeName)
                .inputValueDefinitions(linkPropertyInputDefinitions)
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



}
