-- register_binary_property's FK to schema_property.id was added without ON DELETE CASCADE, unlike
-- every other property-ownership/nesting association table (schema_item_property,
-- schema_trait_property, schema_link_property, schema_property_property all cascade). Deleting a
-- BINARY property that any item had ever set 500'd on a raw FK violation instead of succeeding the
-- way deleting a scalar property (no such FK at all) already does.
ALTER TABLE register_binary_property DROP CONSTRAINT register_binary_property_property_id_fkey;
ALTER TABLE register_binary_property ADD CONSTRAINT register_binary_property_property_id_fkey
    FOREIGN KEY (property_id) REFERENCES schema_property(id) ON DELETE CASCADE;
