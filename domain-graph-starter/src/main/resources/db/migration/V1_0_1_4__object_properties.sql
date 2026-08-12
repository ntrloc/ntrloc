-- Object properties: a property can now own other properties, letting PropertyType.OBJECT become
-- genuinely structured instead of an opaque JSON blob. Mirrors schema_item_property/
-- schema_trait_property/schema_link_property exactly -- containment lives in a join table, not a
-- self-referential column on schema_property, so schema_property itself stays agnostic to what
-- kind of thing owns a given row. Nesting is schema-only: register/ledger storage is untouched,
-- properties inside an object property are addressed by their own id at the same flat level as
-- any other property, same as they already are for item/trait/link ownership.
CREATE TABLE schema_property_property (
    parent_property_id UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
    child_property_id  UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
    PRIMARY KEY (parent_property_id, child_property_id)
);
