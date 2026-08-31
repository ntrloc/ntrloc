-- state-machine:start -- a marker-scoped permission for who may begin an item's participation in a
-- state machine, mirroring marker_grant_transition (execute). Plain existence join: "start" is a
-- single boolean-shaped verb, so the presence of the row is the grant.
CREATE TABLE marker_grant_state_machine_start (
    id               UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_grant_id  UUID NOT NULL REFERENCES marker_grant(id) ON DELETE CASCADE,
    state_machine_id UUID NOT NULL REFERENCES schema_state_machine(id) ON DELETE CASCADE,
    UNIQUE (marker_grant_id, state_machine_id)
);
