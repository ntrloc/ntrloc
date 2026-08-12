package org.ntrloc.graph.db.partition.schema.definition;

// What kind of thing directly owns a property association -- an item type, a trait, a link
// type, or (for a property nested inside an object property) another property.
public enum PropertyContainerKind {
    ITEM, TRAIT, LINK, PROPERTY
}
