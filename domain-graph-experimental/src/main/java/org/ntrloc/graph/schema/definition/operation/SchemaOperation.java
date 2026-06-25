package org.ntrloc.graph.schema.definition.operation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreateItemOperation.class,           name = "CREATE_ITEM"),
        @JsonSubTypes.Type(value = UpdateItemOperation.class,           name = "UPDATE_ITEM"),
        @JsonSubTypes.Type(value = DeleteItemOperation.class,           name = "DELETE_ITEM"),
        @JsonSubTypes.Type(value = CreateItemPropertyOperation.class,   name = "CREATE_ITEM_PROPERTY"),
        @JsonSubTypes.Type(value = CreateLinkPropertyOperation.class,   name = "CREATE_LINK_PROPERTY"),
        @JsonSubTypes.Type(value = UpdatePropertyOperation.class,       name = "UPDATE_PROPERTY"),
        @JsonSubTypes.Type(value = DeletePropertyOperation.class,       name = "DELETE_PROPERTY"),
        @JsonSubTypes.Type(value = CreateLinkOperation.class,           name = "CREATE_LINK"),
        @JsonSubTypes.Type(value = DeleteLinkOperation.class,           name = "DELETE_LINK"),
        @JsonSubTypes.Type(value = UpdatePerspectiveOperation.class,    name = "UPDATE_PERSPECTIVE")
})
public sealed interface SchemaOperation
        permits CreateItemOperation, UpdateItemOperation, DeleteItemOperation,
                CreateItemPropertyOperation, CreateLinkPropertyOperation, UpdatePropertyOperation, DeletePropertyOperation,
                CreateLinkOperation, DeleteLinkOperation, UpdatePerspectiveOperation {
}
