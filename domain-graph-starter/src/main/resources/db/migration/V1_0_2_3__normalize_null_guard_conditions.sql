-- Transitions created via a REST payload carrying "guardCondition": null were storing the JSON
-- literal null ('null'::jsonb) instead of SQL NULL, because the payload's NullNode was serialized
-- as the string "null". SchemaRepository.serializeGuardCondition now treats a NullNode as NULL;
-- this normalizes any rows already written the old way so "has a guard" is a plain IS NOT NULL.
UPDATE schema_state_transition SET guard_condition = NULL WHERE guard_condition = 'null'::jsonb;
