package org.ntrloc.graph.db.partition.schema.definition.mutation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreateItemDefinitionMutation.class,           name = "CREATE_ITEM"),
        @JsonSubTypes.Type(value = UpdateItemDefinitionMutation.class,           name = "UPDATE_ITEM"),
        @JsonSubTypes.Type(value = DeleteItemDefinitionMutation.class,           name = "DELETE_ITEM"),
        @JsonSubTypes.Type(value = CreateTraitDefinitionMutation.class,          name = "CREATE_TRAIT"),
        @JsonSubTypes.Type(value = ImplementTraitMutation.class,                 name = "IMPLEMENT_TRAIT"),
        @JsonSubTypes.Type(value = RemoveTraitMutation.class,                    name = "REMOVE_TRAIT"),
        @JsonSubTypes.Type(value = CreateItemPropertyDefinitionMutation.class,   name = "CREATE_ITEM_PROPERTY"),
        @JsonSubTypes.Type(value = CreateLinkPropertyDefinitionMutation.class,   name = "CREATE_LINK_PROPERTY"),
        @JsonSubTypes.Type(value = UpdatePropertyDefinitionMutation.class,       name = "UPDATE_PROPERTY"),
        @JsonSubTypes.Type(value = DeletePropertyDefinitionMutation.class,       name = "DELETE_PROPERTY"),
        @JsonSubTypes.Type(value = CreateLinkDefinitionMutation.class,           name = "CREATE_LINK"),
        @JsonSubTypes.Type(value = DeleteLinkDefinitionMutation.class,           name = "DELETE_LINK"),
        @JsonSubTypes.Type(value = UpdatePerspectiveDefinitionMutation.class,    name = "UPDATE_PERSPECTIVE"),
        @JsonSubTypes.Type(value = ReplaceControlledListMutation.class,          name = "REPLACE_CONTROLLED_LIST")
})
public sealed interface DefinitionMutation
        permits CreateItemDefinitionMutation, UpdateItemDefinitionMutation, DeleteItemDefinitionMutation,
                CreateTraitDefinitionMutation, ImplementTraitMutation, RemoveTraitMutation,
                CreateItemPropertyDefinitionMutation, CreateLinkPropertyDefinitionMutation, UpdatePropertyDefinitionMutation, DeletePropertyDefinitionMutation,
                CreateLinkDefinitionMutation, DeleteLinkDefinitionMutation, UpdatePerspectiveDefinitionMutation,
                ReplaceControlledListMutation {
}
