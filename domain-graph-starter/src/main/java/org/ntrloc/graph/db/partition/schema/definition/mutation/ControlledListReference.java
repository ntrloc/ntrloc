package org.ntrloc.graph.db.partition.schema.definition.mutation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// SetPropertyControlledListMutation's own per-call choice (mirrors ItemReference, the item/link
// mutation side's identical "new or existing" shape): new points at a list created earlier in this
// same request (by refId, not yet persisted when the batch was submitted); existing points at an
// already-persisted list.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NewControlledListReference.class, name = "NEW"),
        @JsonSubTypes.Type(value = ExistingControlledListReference.class, name = "EXISTING")
})
public sealed interface ControlledListReference permits NewControlledListReference, ExistingControlledListReference {
}
