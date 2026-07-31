package org.ntrloc.graph.db.mutation;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// A link endpoint's per-side choice (Section 7): new points at an item created earlier in
// this same request (by refId, not yet persisted); existing points at an already-persisted item.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = NewItemReference.class, name = "NEW"),
        @JsonSubTypes.Type(value = ExistingItemReference.class, name = "EXISTING")
})
public sealed interface ItemReference permits NewItemReference, ExistingItemReference {
}
