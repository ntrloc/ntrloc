-- Marker Assignment Rules (tracer bullet): a rule binds one item type to one deployed DMN
-- decision. The decision's output declares which marker name(s) should apply -- no marker_id
-- column here, since a rule isn't statically restricted to one marker; safety for removal comes
-- from ledger provenance at evaluation time (see MarkerRuleEvaluationService), not from a static
-- ownership FK. decision_key is a Flowable DMN decision key, not a foreign key -- decisions are
-- deployed/versioned independently via the existing /api/admin/dmn endpoints.

CREATE TABLE authorization_marker_rule (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    name          TEXT NOT NULL,
    item_type_id  UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
    decision_key  TEXT NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_authorization_marker_rule_item_type ON authorization_marker_rule(item_type_id);
