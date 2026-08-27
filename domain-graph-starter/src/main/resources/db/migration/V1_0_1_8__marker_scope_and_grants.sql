-- Marker scope + grant restructuring. See docs/ntrloc-marker-admin-ui-design-notes.md for the full
-- design conversation this implements. Clean break -- no real data exists anywhere, so this drops
-- and rebuilds rather than migrating in place.

-- === Marker scope ===
-- A marker's scope answers two questions only: which item instances is it eligible to be assigned
-- to, and which properties/transitions may a grant under it reference. It conveys no permission on
-- its own (authorization_marker never did, and still doesn't -- this is a pure eligibility
-- constraint on the marker's definition, not a permission).
--
-- scope_id is deliberately unconstrained (no FK) -- it references schema_item.id, schema_trait.id,
-- or schema_entity_link_perspective.id depending on scope_kind, and Postgres can't express a
-- polymorphic FK across three possible target tables on one column. This mirrors an existing
-- precedent already in this schema: schema_entity_link_perspective.entity_id is itself polymorphic
-- between schema_item.id and schema_trait.id, with no FK, validated only at the application layer
-- (SchemaMutationValidation.requireKnownItemOrTrait). A sibling validation function does the same
-- for this three-way scope.
ALTER TABLE authorization_marker
    ADD COLUMN scope_kind TEXT NOT NULL CHECK (scope_kind IN ('ITEM_TYPE', 'TRAIT', 'LINK_PERSPECTIVE')),
    ADD COLUMN scope_id UUID NOT NULL;

-- === Link-targeted markers eliminated ===
-- Markers only ever apply to items, never to links -- see the design doc's "Decision: markers apply
-- to items only" section. A link's own marker table and its grant plumbing are removed outright;
-- link:read/create/delete now live entirely on marker_grant_link_perspective, anchored to the
-- source item's own marker.
DROP TABLE register_link_marker;

-- === Grants ===
-- authorization_grant (one row per marker x principal x operation[x property], repeating the
-- (marker, principal) pair once per operation) is replaced by a normalized shape: one marker_grant
-- entity per (marker, principal) pair, plus one child table per object kind a grant can reference.
--
-- Whether an object kind needs its own join table depends on whether it has real multiplicity for a
-- given grant, not on which object kind it is. Item-level verbs (view/delete the item that carries
-- the marker) are strictly 1:1 -- there's only ever one such item per grant -- so they're flat
-- columns on marker_grant itself. Properties, link perspectives, link properties, and transitions
-- are all genuinely one-to-many per grant (a marker can reference several properties, several of an
-- item type's perspectives with independently different verbs, etc.), so each gets a real child
-- table keyed by the specific object referenced.
DROP TABLE authorization_grant;

CREATE TABLE marker_grant (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_id      UUID NOT NULL REFERENCES authorization_marker(id) ON DELETE CASCADE,
    principal_type TEXT NOT NULL CHECK (principal_type IN ('USER', 'GROUP')),
    principal_id   UUID NOT NULL,
    item_can_read   BOOLEAN NOT NULL DEFAULT FALSE,
    item_can_delete BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (marker_id, principal_type, principal_id)
);

-- can_download only meaningfully applies when the referenced property is binary-typed; a binary
-- property doesn't support ordinary write at all (MutationRequestProcessor: "binary properties
-- cannot be set via mutation" -- uploads are a separate mechanism), so can_write is simply unused
-- for those rows rather than being a distinct illegal state to guard against.
CREATE TABLE marker_grant_property (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_grant_id UUID NOT NULL REFERENCES marker_grant(id) ON DELETE CASCADE,
    property_id     UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
    can_read        BOOLEAN NOT NULL DEFAULT FALSE,
    can_write       BOOLEAN NOT NULL DEFAULT FALSE,
    can_download    BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (marker_grant_id, property_id)
);

-- perspective_id resolves unambiguously to one link definition (schema_entity_link_perspective.
-- link_definition_id), so link:create/read/delete permission -- and, symmetrically, which link
-- instances are even visible -- is anchored to the *source* item's own marker via a named
-- perspective, never to a marker on the link itself (link markers don't exist).
CREATE TABLE marker_grant_link_perspective (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_grant_id UUID NOT NULL REFERENCES marker_grant(id) ON DELETE CASCADE,
    perspective_id  UUID NOT NULL REFERENCES schema_entity_link_perspective(id) ON DELETE CASCADE,
    can_create      BOOLEAN NOT NULL DEFAULT FALSE,
    can_read        BOOLEAN NOT NULL DEFAULT FALSE,
    can_delete      BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (marker_grant_id, perspective_id)
);

-- A link's own properties (symmetric regardless of which side you view the link from -- see the
-- design doc's link-perspective framing-only conclusion), same shape as marker_grant_property.
CREATE TABLE marker_grant_link_property (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_grant_id UUID NOT NULL REFERENCES marker_grant(id) ON DELETE CASCADE,
    property_id     UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
    can_read        BOOLEAN NOT NULL DEFAULT FALSE,
    can_write       BOOLEAN NOT NULL DEFAULT FALSE,
    can_download    BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (marker_grant_id, property_id)
);

-- Plain existence join (like schema_item_trait) -- execute is a single boolean-shaped verb, no
-- read/write/download distinction needed, so presence of the row alone is the grant.
CREATE TABLE marker_grant_transition (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_grant_id UUID NOT NULL REFERENCES marker_grant(id) ON DELETE CASCADE,
    transition_id   UUID NOT NULL REFERENCES schema_state_transition(id) ON DELETE CASCADE,
    UNIQUE (marker_grant_id, transition_id)
);
