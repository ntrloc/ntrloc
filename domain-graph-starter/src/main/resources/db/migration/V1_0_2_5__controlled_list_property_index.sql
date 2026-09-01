-- Reverse lookup "which properties use this controlled list" now runs on every admin-schema
-- rebuild (SchemaViewBuilder.buildAdminSchema), and the FK's ON DELETE SET NULL scans this column
-- when a list is deleted. Index it.
CREATE INDEX schema_property_controlled_list_id_idx ON schema_property (controlled_list_id);
