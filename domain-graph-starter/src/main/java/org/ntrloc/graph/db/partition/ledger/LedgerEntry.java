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
        @JsonSubTypes.Type(value = LinkDeleteEntry.class, name = "LINK_DELETE")
})
public sealed interface LedgerEntry
        permits ItemCreateEntry, ItemUpdateEntry, ItemDeleteEntry,
                LinkCreateEntry, LinkUpdateEntry, LinkDeleteEntry {
}
