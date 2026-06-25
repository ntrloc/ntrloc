package org.ntrloc.graph.schema.definition.mutation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreateItemDefinitionMutation.class,           name = "CREATE_ITEM"),
        @JsonSubTypes.Type(value = UpdateItemDefinitionMutation.class,           name = "UPDATE_ITEM"),
        @JsonSubTypes.Type(value = DeleteItemDefinitionMutation.class,           name = "DELETE_ITEM"),
        @JsonSubTypes.Type(value = CreateItemPropertyDefinitionMutation.class,   name = "CREATE_ITEM_PROPERTY"),
        @JsonSubTypes.Type(value = CreateLinkPropertyDefinitionMutation.class,   name = "CREATE_LINK_PROPERTY"),
        @JsonSubTypes.Type(value = UpdatePropertyDefinitionMutation.class,       name = "UPDATE_PROPERTY"),
        @JsonSubTypes.Type(value = DeletePropertyDefinitionMutation.class,       name = "DELETE_PROPERTY"),
        @JsonSubTypes.Type(value = CreateLinkDefinitionMutation.class,           name = "CREATE_LINK"),
        @JsonSubTypes.Type(value = DeleteLinkDefinitionMutation.class,           name = "DELETE_LINK"),
        @JsonSubTypes.Type(value = UpdatePerspectiveDefinitionMutation.class,    name = "UPDATE_PERSPECTIVE")
})
public sealed interface DefinitionMutation
        permits CreateItemDefinitionMutation, UpdateItemDefinitionMutation, DeleteItemDefinitionMutation,
                CreateItemPropertyDefinitionMutation, CreateLinkPropertyDefinitionMutation, UpdatePropertyDefinitionMutation, DeletePropertyDefinitionMutation,
                CreateLinkDefinitionMutation, DeleteLinkDefinitionMutation, UpdatePerspectiveDefinitionMutation {
}
