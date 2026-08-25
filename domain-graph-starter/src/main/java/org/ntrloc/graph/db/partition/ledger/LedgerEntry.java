package org.ntrloc.graph.db.partition.ledger;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ItemCreateEntry.class, name = "ITEM_CREATE"),
        @JsonSubTypes.Type(value = ItemUpdateEntry.class, name = "ITEM_UPDATE"),
        @JsonSubTypes.Type(value = ItemDeleteEntry.class, name = "ITEM_DELETE"),
        @JsonSubTypes.Type(value = LinkCreateEntry.class, name = "LINK_CREATE"),
        @JsonSubTypes.Type(value = LinkUpdateEntry.class, name = "LINK_UPDATE"),
        @JsonSubTypes.Type(value = LinkDeleteEntry.class, name = "LINK_DELETE"),
        @JsonSubTypes.Type(value = ItemMarkerAddEntry.class, name = "ITEM_MARKER_ADD"),
        @JsonSubTypes.Type(value = ItemMarkerRemoveEntry.class, name = "ITEM_MARKER_REMOVE"),
        @JsonSubTypes.Type(value = LinkMarkerAddEntry.class, name = "LINK_MARKER_ADD"),
        @JsonSubTypes.Type(value = LinkMarkerRemoveEntry.class, name = "LINK_MARKER_REMOVE")
})
public sealed interface LedgerEntry
        permits ItemCreateEntry, ItemUpdateEntry, ItemDeleteEntry,
                LinkCreateEntry, LinkUpdateEntry, LinkDeleteEntry,
                ItemMarkerAddEntry, ItemMarkerRemoveEntry,
                LinkMarkerAddEntry, LinkMarkerRemoveEntry {
}
