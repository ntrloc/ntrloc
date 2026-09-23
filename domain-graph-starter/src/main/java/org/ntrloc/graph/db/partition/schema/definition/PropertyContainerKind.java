package org.ntrloc.graph.db.partition.schema.definition;

// What kind of thing directly owns a property or a property group -- an item type, a trait, a
// link type, or another property group. Exactly one at a time (see the parent columns on
// schema_property/schema_property_group).
public enum PropertyContainerKind {
    ITEM, TRAIT, LINK, GROUP
}
