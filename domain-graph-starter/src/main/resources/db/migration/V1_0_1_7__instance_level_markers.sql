-- Instance-level marker assignment (item/link -> marker) and the property-scoped grant column
-- that lets property:read/property:write/link_property:read/link_property:write be granted per
-- property rather than blanket. See docs/ntrloc-acl-design-notes.md: "Marker storage: join
-- tables, not a multi-value field", "Grants need a property-scope column".

CREATE TABLE register_item_marker (
    id               UUID PRIMARY KEY DEFAULT uuidv7(),
    register_item_id UUID NOT NULL REFERENCES register_item(id) ON DELETE CASCADE,
    marker_id        UUID NOT NULL REFERENCES authorization_marker(id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (register_item_id, marker_id)
);
CREATE INDEX idx_register_item_marker_marker ON register_item_marker(marker_id);

CREATE TABLE register_link_marker (
    id               UUID PRIMARY KEY DEFAULT uuidv7(),
    register_link_id UUID NOT NULL REFERENCES register_link(id) ON DELETE CASCADE,
    marker_id        UUID NOT NULL REFERENCES authorization_marker(id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (register_link_id, marker_id)
);
CREATE INDEX idx_register_link_marker_marker ON register_link_marker(marker_id);

-- property_id scopes property:read/property:write/link_property:read/link_property:write grants
-- to a specific property; NULL (and required NULL) for item:read/item:delete/link:read/link:delete.
-- schema_item_property.property_id and schema_link_property.property_id both reference
-- schema_property(id) -- one column covers both item and link property grants, disambiguated by
-- `operation` alone.
ALTER TABLE authorization_grant
    ADD COLUMN property_id UUID NULL REFERENCES schema_property(id) ON DELETE CASCADE;

ALTER TABLE authorization_grant
    ADD CONSTRAINT authorization_grant_property_scope_matches_operation
    CHECK ((property_id IS NOT NULL) = (operation IN
        ('property:read', 'property:write', 'link_property:read', 'link_property:write')));

-- Replaces the old UNIQUE (marker_id, principal_type, principal_id, operation): without
-- NULLS NOT DISTINCT, Postgres treats every property_id-less (item/link-level) grant row as
-- distinct from every other, since NULL <> NULL for uniqueness purposes -- silently allowing
-- duplicate item:read grants for the same marker/principal. The old constraint's name is
-- Postgres-auto-generated and would be silently truncated/mangled past 63 bytes if guessed by
-- concatenation, so look it up rather than hardcode it -- authorization_grant has exactly one
-- UNIQUE constraint today, so contype = 'u' alone identifies it unambiguously.
DO $$
DECLARE
    old_unique_constraint text;
BEGIN
    SELECT conname INTO old_unique_constraint
    FROM pg_constraint
    WHERE conrelid = 'authorization_grant'::regclass AND contype = 'u';

    EXECUTE format('ALTER TABLE authorization_grant DROP CONSTRAINT %I', old_unique_constraint);
END $$;

ALTER TABLE authorization_grant
    ADD CONSTRAINT authorization_grant_unique_scope
    UNIQUE NULLS NOT DISTINCT (marker_id, principal_type, principal_id, operation, property_id);
