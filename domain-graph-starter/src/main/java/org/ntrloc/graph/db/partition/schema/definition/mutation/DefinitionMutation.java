package org.ntrloc.graph.db.partition.schema.definition.mutation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreateItemDefinitionMutation.class,           name = "CREATE_ITEM"),
        @JsonSubTypes.Type(value = UpdateItemDefinitionMutation.class,           name = "UPDATE_ITEM"),
        @JsonSubTypes.Type(value = DeleteItemDefinitionMutation.class,           name = "DELETE_ITEM"),
        @JsonSubTypes.Type(value = CreateTraitDefinitionMutation.class,          name = "CREATE_TRAIT"),
        @JsonSubTypes.Type(value = DeleteTraitDefinitionMutation.class,          name = "DELETE_TRAIT"),
        @JsonSubTypes.Type(value = ImplementTraitMutation.class,                 name = "IMPLEMENT_TRAIT"),
        @JsonSubTypes.Type(value = RemoveTraitMutation.class,                    name = "REMOVE_TRAIT"),
        @JsonSubTypes.Type(value = CreateItemPropertyDefinitionMutation.class,   name = "CREATE_ITEM_PROPERTY"),
        @JsonSubTypes.Type(value = CreateLinkPropertyDefinitionMutation.class,   name = "CREATE_LINK_PROPERTY"),
        @JsonSubTypes.Type(value = CreatePropertyPropertyDefinitionMutation.class, name = "CREATE_OBJECT_PROPERTY_CHILD"),
        @JsonSubTypes.Type(value = UpdatePropertyDefinitionMutation.class,       name = "UPDATE_PROPERTY"),
        @JsonSubTypes.Type(value = DeletePropertyDefinitionMutation.class,       name = "DELETE_PROPERTY"),
        @JsonSubTypes.Type(value = MovePropertyDefinitionMutation.class,        name = "MOVE_PROPERTY"),
        @JsonSubTypes.Type(value = CreateLinkDefinitionMutation.class,           name = "CREATE_LINK"),
        @JsonSubTypes.Type(value = DeleteLinkDefinitionMutation.class,           name = "DELETE_LINK"),
        @JsonSubTypes.Type(value = UpdatePerspectiveDefinitionMutation.class,    name = "UPDATE_PERSPECTIVE"),
        @JsonSubTypes.Type(value = ReplaceControlledListMutation.class,          name = "REPLACE_CONTROLLED_LIST"),
        @JsonSubTypes.Type(value = CreateControlledListMutation.class,           name = "CREATE_CONTROLLED_LIST"),
        @JsonSubTypes.Type(value = UpdateControlledListMutation.class,           name = "UPDATE_CONTROLLED_LIST"),
        @JsonSubTypes.Type(value = DeleteControlledListMutation.class,           name = "DELETE_CONTROLLED_LIST"),
        @JsonSubTypes.Type(value = SetPropertyControlledListMutation.class,      name = "SET_PROPERTY_CONTROLLED_LIST"),
        @JsonSubTypes.Type(value = CreateStateMachineMutation.class,             name = "CREATE_STATE_MACHINE"),
        @JsonSubTypes.Type(value = UpdateStateMachineMutation.class,             name = "UPDATE_STATE_MACHINE"),
        @JsonSubTypes.Type(value = DeleteStateMachineMutation.class,             name = "DELETE_STATE_MACHINE"),
        @JsonSubTypes.Type(value = CreateStateMutation.class,                    name = "CREATE_STATE"),
        @JsonSubTypes.Type(value = UpdateStateMutation.class,                    name = "UPDATE_STATE"),
        @JsonSubTypes.Type(value = DeleteStateMutation.class,                    name = "DELETE_STATE"),
        @JsonSubTypes.Type(value = CreateTransitionMutation.class,               name = "CREATE_TRANSITION"),
        @JsonSubTypes.Type(value = UpdateTransitionMutation.class,               name = "UPDATE_TRANSITION"),
        @JsonSubTypes.Type(value = DeleteTransitionMutation.class,               name = "DELETE_TRANSITION")
})
public sealed interface DefinitionMutation
        permits CreateItemDefinitionMutation, UpdateItemDefinitionMutation, DeleteItemDefinitionMutation,
                CreateTraitDefinitionMutation, DeleteTraitDefinitionMutation, ImplementTraitMutation, RemoveTraitMutation,
                CreateItemPropertyDefinitionMutation, CreateLinkPropertyDefinitionMutation, CreatePropertyPropertyDefinitionMutation,
                UpdatePropertyDefinitionMutation, DeletePropertyDefinitionMutation, MovePropertyDefinitionMutation,
                CreateLinkDefinitionMutation, DeleteLinkDefinitionMutation, UpdatePerspectiveDefinitionMutation,
                ReplaceControlledListMutation,
                CreateControlledListMutation, UpdateControlledListMutation, DeleteControlledListMutation,
                SetPropertyControlledListMutation,
                CreateStateMachineMutation, UpdateStateMachineMutation, DeleteStateMachineMutation,
                CreateStateMutation, UpdateStateMutation, DeleteStateMutation,
                CreateTransitionMutation, UpdateTransitionMutation, DeleteTransitionMutation {
}
