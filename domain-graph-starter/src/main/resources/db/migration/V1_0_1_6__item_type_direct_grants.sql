-- Type visibility is a direct (principal, item_type, permission) grant, not marker-mediated.
-- See docs/ntrloc-security-projections-summary.md "Type Visibility" and
-- docs/ntrloc-acl-design-notes.md's 2026-08-24 pivot note for why this replaces the
-- marker-routed item-type read check the first ACL slice shipped with.

DROP TABLE authorization_item_type_marker;

-- Tracks whether DefaultGroupInitializer has ever made its default-open-read-until-narrowed
-- decision for this item type, independent of whether that grant currently exists. Without this,
-- an admin's explicit revocation of "everyone"'s default read is silently undone the next time
-- the boot-time backfill (grantReadForUncoveredItemTypes) runs, since it would otherwise judge
-- "covered" by the grant's current presence rather than by whether a decision was ever made.
ALTER TABLE schema_item ADD COLUMN default_visibility_decided BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE authorization_item_type_grant (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    item_type_id   UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
    principal_type TEXT NOT NULL CHECK (principal_type IN ('USER', 'GROUP')),
    principal_id   UUID NOT NULL,
    permission     TEXT NOT NULL CHECK (permission IN ('item-type:read', 'item-type:create')),
    UNIQUE (item_type_id, principal_type, principal_id, permission)
);
