-- Item type display label: an optional SpEL pattern (evaluated against the item's own properties
-- at projection time) that computes a human-readable displayLabel for every projected instance of
-- this type. Null means "no pattern of this type's own" -- inherited from the nearest supertype
-- that defines one, resolved at projection time (RegisterPartitionManager), not baked in here.

ALTER TABLE schema_item ADD COLUMN display_label_pattern TEXT;
