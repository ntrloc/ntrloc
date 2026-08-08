-- 1.0.1's fix for the defect 1.0.0 shipped with: schema_entity_link_perspective's
-- UNIQUE(entity_id, link_definition_id) (see V1_0_0_1__baseline.sql) made same-type/same-trait
-- self-links (e.g. Person "mother of" Person) impossible -- see SchemaMutationValidation.
-- requireConsistentPerspectiveTarget for the guardrail that replaced it. Runs for real on every
-- install, fresh or upgraded, since the baseline always creates the constraint; IF EXISTS is
-- defensive only, in case someone dropped it by hand ahead of this migration.
ALTER TABLE schema_entity_link_perspective
    DROP CONSTRAINT IF EXISTS schema_entity_link_perspective_entity_id_link_definition_id_key;
