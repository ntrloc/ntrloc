-- Baseline: a faithful transcription of every table/index previously created ad hoc by the
-- *Initializer @PostConstruct classes (SchemaInitializer, RegisterInitializer, SecurityInitializer,
-- AuthorizationInitializer, BinaryInitializer, LedgerInitializer, ProcessGroupInitializer,
-- ProcessPersistenceInitializer, DecisionPersistenceInitializer -- all now deleted, Flyway owns
-- this DDL from here on). Statement order follows the FK dependency graph, not source-file order.
--
-- security_user.email is folded directly into its CREATE TABLE (SecurityInitializer used to patch
-- it on via a separate ALTER TABLE ... ADD COLUMN IF NOT EXISTS, evidence of the exact problem
-- Flyway now replaces -- this baseline represents current target shape, not that history).
--
-- 2026-09-19: this file is now the *single* consolidated baseline. The eighteen incremental
-- migrations that used to follow it (V1_0_1_1 .. V1_0_2_7) were folded in -- nothing needed an
-- upgrade path, the database is recreated from scratch -- and the schema itself was reworked at
-- the same time: object properties are gone (PropertyType.OBJECT no longer exists), replaced by
-- schema_property_group, and every property/group now has exactly one parent, expressed as parent
-- columns with a CHECK, instead of the four ownership/nesting join tables (schema_item_property,
-- schema_trait_property, schema_link_property, schema_property_property). See
-- docs/ntrloc-dynamic-properties-design-notes.md sections 4 and 5. Flyway stays wired in for
-- future migrations; the history lives in git.

-- === schema_* (SchemaInitializer) ===

-- supertype_id: a nullable, self-referencing supertype forms a single-parent tree; abstract blocks
-- direct instantiation of a type meant only to be extended. No cascade on supertype_id --
-- re-parenting/deleting a supertype is a live, read-tolerant schema edit handled at the
-- application layer (SchemaMutationValidation), not something the DB should enforce via cascade.
--
-- display_label_pattern: an optional SpEL pattern (evaluated against the item's own properties at
-- projection time) computing a human-readable displayLabel for every projected instance. Null means
-- "no pattern of this type's own" -- inherited from the nearest supertype that defines one,
-- resolved at projection time (RegisterPartitionManager), not baked in here.
--
-- default_visibility_decided: tracks whether DefaultGroupInitializer has ever made its
-- default-open-read-until-narrowed decision for this type, independent of whether that grant
-- currently exists -- otherwise an admin's explicit revocation of "everyone"'s default read would
-- be silently undone by the next boot-time backfill.
CREATE TABLE schema_item (
    id                         UUID PRIMARY KEY DEFAULT uuidv7(),
    name                       TEXT NOT NULL UNIQUE,
    description                TEXT,
    supertype_id               UUID REFERENCES schema_item(id),
    abstract                   BOOLEAN NOT NULL DEFAULT FALSE,
    display_label_pattern      TEXT,
    default_visibility_decided BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE schema_trait (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    name        TEXT NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE schema_link (
    id UUID PRIMARY KEY DEFAULT uuidv7()
);

CREATE TABLE schema_controlled_list (
    id         UUID PRIMARY KEY DEFAULT uuidv7(),
    name       TEXT NOT NULL,
    value_type TEXT NOT NULL
);

-- A property group is purely structural: it carries a name and a place in the tree (paths, name
-- scoping), never a value, never a grant -- nothing can reference it from marker_grant_property.
-- It replaces what used to be an OBJECT-typed schema_property row. Exactly one parent: an item
-- type, a trait, a link type, or another group. Sibling-name uniqueness (across properties and
-- groups, which live in separate tables) is enforced in the application (SchemaMutationValidation),
-- not here -- a DB constraint can't span two tables.
CREATE TABLE schema_property_group (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    name            TEXT NOT NULL,
    description     TEXT,
    parent_item_id  UUID REFERENCES schema_item(id)  ON DELETE CASCADE,
    parent_trait_id UUID REFERENCES schema_trait(id) ON DELETE CASCADE,
    parent_link_id  UUID REFERENCES schema_link(id)  ON DELETE CASCADE,
    parent_group_id UUID REFERENCES schema_property_group(id) ON DELETE CASCADE,
    CHECK (num_nonnulls(parent_item_id, parent_trait_id, parent_link_id, parent_group_id) = 1)
);
CREATE INDEX schema_property_group_item_idx  ON schema_property_group (parent_item_id)  WHERE parent_item_id  IS NOT NULL;
CREATE INDEX schema_property_group_trait_idx ON schema_property_group (parent_trait_id) WHERE parent_trait_id IS NOT NULL;
CREATE INDEX schema_property_group_link_idx  ON schema_property_group (parent_link_id)  WHERE parent_link_id  IS NOT NULL;
CREATE INDEX schema_property_group_group_idx ON schema_property_group (parent_group_id) WHERE parent_group_id IS NOT NULL;

-- A property is a leaf that carries a value -- the only thing a grant can target. Names are not
-- globally unique, only among siblings under the same parent (application-enforced, see above): two
-- different types legitimately have their own distinct property row (different id) that happens to
-- share a name, e.g. Product.name and Contributor.name. Exactly one parent, same shape as groups.
--
-- facetable is an explicit admin declaration, not inferred purely from type/cardinality/
-- controlled-list: a data model carrying 50+ controlled-list-backed fields would otherwise have
-- every one of them auto-treated as facetable (and computed by default whenever a caller passes an
-- empty facets list). The structural rule (SINGLE cardinality, and either STRING with a controlled
-- list or BOOLEAN) still gates *eligibility*; this column is the separate, admin-controlled opt-in.
CREATE TABLE schema_property (
    id                 UUID PRIMARY KEY DEFAULT uuidv7(),
    name               TEXT NOT NULL,
    description        TEXT,
    type               TEXT NOT NULL,
    cardinality        TEXT NOT NULL,
    usage              TEXT NOT NULL,
    controlled_list_id UUID REFERENCES schema_controlled_list(id) ON DELETE SET NULL,
    facetable          BOOLEAN NOT NULL DEFAULT FALSE,
    parent_item_id     UUID REFERENCES schema_item(id)  ON DELETE CASCADE,
    parent_trait_id    UUID REFERENCES schema_trait(id) ON DELETE CASCADE,
    parent_link_id     UUID REFERENCES schema_link(id)  ON DELETE CASCADE,
    parent_group_id    UUID REFERENCES schema_property_group(id) ON DELETE CASCADE,
    CHECK (num_nonnulls(parent_item_id, parent_trait_id, parent_link_id, parent_group_id) = 1)
);
-- "Which properties use this controlled list" runs on every admin-schema rebuild, and the FK's
-- ON DELETE SET NULL scans this column when a list is deleted.
CREATE INDEX schema_property_controlled_list_id_idx ON schema_property (controlled_list_id);
CREATE INDEX schema_property_item_idx  ON schema_property (parent_item_id)  WHERE parent_item_id  IS NOT NULL;
CREATE INDEX schema_property_trait_idx ON schema_property (parent_trait_id) WHERE parent_trait_id IS NOT NULL;
CREATE INDEX schema_property_link_idx  ON schema_property (parent_link_id)  WHERE parent_link_id  IS NOT NULL;
CREATE INDEX schema_property_group_idx ON schema_property (parent_group_id) WHERE parent_group_id IS NOT NULL;

CREATE TABLE schema_item_trait (
    item_id  UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
    trait_id UUID NOT NULL REFERENCES schema_trait(id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, trait_id)
);

-- A state machine is a named, independently-identified entity so multiple can exist per item type
-- (e.g. a Product might have both an "ISBN Approval" and a "Fulfillment" machine) -- states/
-- transitions hang off the machine, not directly off the item.
CREATE TABLE schema_state_machine (
    id                 UUID PRIMARY KEY DEFAULT uuidv7(),
    item_definition_id UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
    name               TEXT NOT NULL,
    description        TEXT,
    UNIQUE (item_definition_id, name)
);

-- Every machine owns exactly one START and one END state (kind = 'START' / 'END'), created with the
-- machine and undeletable. NORMAL states are the user-defined ones. START has at most one outgoing
-- transition (to a NORMAL state, no guard); END has no outgoing transitions. Pseudostate rows carry
-- a sentinel name ('__start__' / '__end__'); the editor renders them by kind, not name.
--
-- entry_marker_decision_key: a NORMAL state may declare a DMN decision (by key, deployed
-- independently like entry_process_id) that runs on entry: it looks at the item's property values
-- and returns marker names to apply for as long as the item is in that state. Nullable;
-- pseudostates never get one.
CREATE TABLE schema_state (
    id                        UUID PRIMARY KEY DEFAULT uuidv7(),
    state_machine_id          UUID NOT NULL REFERENCES schema_state_machine(id) ON DELETE CASCADE,
    name                      TEXT NOT NULL,
    description               TEXT,
    kind                      TEXT NOT NULL DEFAULT 'NORMAL' CHECK (kind IN ('NORMAL', 'START', 'END')),
    entry_process_id          TEXT,
    exit_process_id           TEXT,
    entry_marker_decision_key TEXT,
    UNIQUE (state_machine_id, name)
);

CREATE TABLE schema_state_transition (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    from_state_id   UUID NOT NULL REFERENCES schema_state(id) ON DELETE CASCADE,
    to_state_id     UUID NOT NULL REFERENCES schema_state(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    description     TEXT,
    process_id      TEXT,
    guard_condition JSONB,
    UNIQUE (from_state_id, to_state_id)
);

-- entity_id has no FK constraint here -- it's deliberately polymorphic, holding either a
-- schema_item.id or a schema_trait.id (the two tables share no common parent to reference now that
-- schema_entity is gone; see SchemaManager.applyMutations' validation of this value against both
-- tables at write time, the only place that constraint can be enforced).
--
-- No UNIQUE(entity_id, link_definition_id): it made same-type/same-trait self-links (e.g. Person
-- "mother of" Person) impossible -- see SchemaMutationValidation.requireConsistentPerspectiveTarget
-- for the guardrail that replaced it.
CREATE TABLE schema_entity_link_perspective (
    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
    entity_id           UUID NOT NULL,
    link_definition_id  UUID NOT NULL REFERENCES schema_link(id) ON DELETE CASCADE,
    name                TEXT NOT NULL,
    description         TEXT,
    minimum_cardinality INT NOT NULL CHECK (minimum_cardinality >= 0),
    maximum_cardinality INT CHECK (maximum_cardinality IS NULL OR maximum_cardinality >= minimum_cardinality)
);

-- === security_* (SecurityInitializer) ===

-- is_superuser lives here (not on security_local_credentials) because it's an identity attribute
-- independent of how the principal authenticated -- a superuser should bypass marker authorization
-- the same way whether they logged in locally, via LDAP, OAuth, or the stand-in header, per the
-- design doc's "superusers bypass all policy" principle.
CREATE TABLE security_user (
    id           UUID PRIMARY KEY DEFAULT uuidv7(),
    external_id  TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    email        TEXT,
    is_superuser BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE security_user_group (
    id   UUID PRIMARY KEY DEFAULT uuidv7(),
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE security_user_group_member (
    user_id  UUID NOT NULL REFERENCES security_user(id)  ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES security_user_group(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, group_id)
);

-- A group can itself be a member of another group -- member_group_id is a member of group_id,
-- exactly the same shape as security_user_group_member's user_id being a member of group_id. A separate
-- table rather than making security_user_group_member's user_id polymorphic: two real FKs beat one loose
-- one. Self-membership is rejected by the CHECK; deeper cycles (A member of B member of A) are
-- rejected at the application layer (SecurityRepository.addGroupToGroup) -- a CHECK constraint
-- can't express "no path already exists back to this row."
CREATE TABLE security_user_group_member_group (
    member_group_id UUID NOT NULL REFERENCES security_user_group(id) ON DELETE CASCADE,
    group_id        UUID NOT NULL REFERENCES security_user_group(id) ON DELETE CASCADE,
    PRIMARY KEY (member_group_id, group_id),
    CHECK (member_group_id != group_id)
);

CREATE TABLE security_local_credentials (
    user_id       UUID PRIMARY KEY REFERENCES security_user(id) ON DELETE CASCADE,
    email         TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    active        BOOLEAN NOT NULL DEFAULT TRUE
);

-- Only the SHA-256 hash of the token is ever stored -- the raw token is shown exactly once, at
-- creation. Revocation is a hard delete, not a flag.
CREATE TABLE security_personal_access_token (
    id         UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id    UUID NOT NULL REFERENCES security_user(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    name       TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ
);

-- === authorization_* (AuthorizationInitializer) ===

-- A marker's scope answers two questions only: which item instances is it eligible to be assigned
-- to, and which properties/transitions may a grant under it reference. It conveys no permission on
-- its own -- this is a pure eligibility constraint on the marker's definition.
--
-- scope_id is deliberately unconstrained (no FK) -- it references schema_item.id, schema_trait.id,
-- or schema_entity_link_perspective.id depending on scope_kind, and Postgres can't express a
-- polymorphic FK across three possible target tables on one column. Same precedent as
-- schema_entity_link_perspective.entity_id: validated only at the application layer.
CREATE TABLE authorization_marker (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    name        TEXT NOT NULL UNIQUE,
    description TEXT,
    scope_kind  TEXT NOT NULL CHECK (scope_kind IN ('ITEM_TYPE', 'TRAIT', 'LINK_PERSPECTIVE')),
    scope_id    UUID NOT NULL
);

-- Type visibility is a direct (principal, item_type, permission) grant, not marker-mediated. See
-- docs/ntrloc-security-projections-summary.md "Type Visibility".
CREATE TABLE authorization_item_type_grant (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    item_type_id   UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
    principal_type TEXT NOT NULL CHECK (principal_type IN ('USER', 'USER_GROUP')),
    principal_id   UUID NOT NULL,
    permission     TEXT NOT NULL CHECK (permission IN ('item-type:read', 'item-type:create')),
    UNIQUE (item_type_id, principal_type, principal_id, permission)
);

-- Marker Assignment Rules: a rule binds one item type to one deployed DMN decision. The decision's
-- output declares which marker name(s) should apply -- no marker_id column here, since a rule isn't
-- statically restricted to one marker; safety for removal comes from ledger provenance at
-- evaluation time (see MarkerRuleEvaluationService). decision_key is a Flowable DMN decision key,
-- not a foreign key -- decisions are deployed/versioned independently via /api/admin/dmn.
CREATE TABLE authorization_marker_rule (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    name          TEXT NOT NULL,
    item_type_id  UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
    decision_key  TEXT NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_authorization_marker_rule_item_type ON authorization_marker_rule(item_type_id);

-- One marker_grant per (marker, principal) pair, plus one child table per object kind a grant can
-- reference. Whether an object kind needs its own join table depends on whether it has real
-- multiplicity for a given grant: item-level verbs (view/delete the item that carries the marker)
-- are strictly 1:1, so they're flat columns here; properties, link perspectives, link properties,
-- and transitions are all genuinely one-to-many per grant, so each gets a child table.
CREATE TABLE marker_grant (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_id      UUID NOT NULL REFERENCES authorization_marker(id) ON DELETE CASCADE,
    principal_type TEXT NOT NULL CHECK (principal_type IN ('USER', 'USER_GROUP')),
    principal_id   UUID NOT NULL,
    item_can_read   BOOLEAN NOT NULL DEFAULT FALSE,
    item_can_delete BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (marker_id, principal_type, principal_id)
);

-- Only leaf properties are ever grantable (property groups have no table a grant could reference).
-- For a binary property, read is equivalent to download -- if a principal can read the property
-- they can fetch its bytes; there is no second gate -- and can_write is simply unused (binary
-- properties aren't set via ordinary mutation; uploads are a separate mechanism).
CREATE TABLE marker_grant_property (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_grant_id UUID NOT NULL REFERENCES marker_grant(id) ON DELETE CASCADE,
    property_id     UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
    can_read        BOOLEAN NOT NULL DEFAULT FALSE,
    can_write       BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (marker_grant_id, property_id)
);

-- perspective_id resolves unambiguously to one link definition, so link:create/read/delete
-- permission -- and, symmetrically, which link instances are even visible -- is anchored to the
-- *source* item's own marker via a named perspective (markers only ever apply to items).
CREATE TABLE marker_grant_link_perspective (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_grant_id UUID NOT NULL REFERENCES marker_grant(id) ON DELETE CASCADE,
    perspective_id  UUID NOT NULL REFERENCES schema_entity_link_perspective(id) ON DELETE CASCADE,
    can_create      BOOLEAN NOT NULL DEFAULT FALSE,
    can_read        BOOLEAN NOT NULL DEFAULT FALSE,
    can_delete      BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (marker_grant_id, perspective_id)
);

-- A link's own properties, same shape as marker_grant_property.
CREATE TABLE marker_grant_link_property (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_grant_id UUID NOT NULL REFERENCES marker_grant(id) ON DELETE CASCADE,
    property_id     UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
    can_read        BOOLEAN NOT NULL DEFAULT FALSE,
    can_write       BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (marker_grant_id, property_id)
);

-- Plain existence join -- execute is a single boolean-shaped verb, so presence of the row is the
-- grant.
CREATE TABLE marker_grant_transition (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_grant_id UUID NOT NULL REFERENCES marker_grant(id) ON DELETE CASCADE,
    transition_id   UUID NOT NULL REFERENCES schema_state_transition(id) ON DELETE CASCADE,
    UNIQUE (marker_grant_id, transition_id)
);

-- state-machine:start -- who may begin an item's participation in a state machine, mirroring
-- marker_grant_transition (execute). Plain existence join.
CREATE TABLE marker_grant_state_machine_start (
    id               UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_grant_id  UUID NOT NULL REFERENCES marker_grant(id) ON DELETE CASCADE,
    state_machine_id UUID NOT NULL REFERENCES schema_state_machine(id) ON DELETE CASCADE,
    UNIQUE (marker_grant_id, state_machine_id)
);

-- === process_group* (ProcessGroupInitializer) ===
-- Deliberately separate from security_user_group: a process-assignment group (who can pick up a User
-- Task) is a different concept from a permission group (what a set of users can do to schema/graph
-- data) even though both are "a group of users" -- coincidentally similar shape, unrelated
-- lifecycle and ownership.

CREATE TABLE process_group (
    id   UUID PRIMARY KEY DEFAULT uuidv7(),
    name TEXT NOT NULL UNIQUE
);

-- user_id references security_user directly -- individual identity is shared app-wide, it's
-- specifically the grouping mechanism that's kept separate from security_user_group.
CREATE TABLE process_group_member (
    group_id UUID NOT NULL REFERENCES process_group(id) ON DELETE CASCADE,
    user_id  UUID NOT NULL REFERENCES security_user(id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);

-- === register_* (RegisterInitializer) ===
-- The shared, non-per-type register tables. Per-item-type/per-link-type tables
-- (register_item_<uuid>/register_link_<uuid>) remain created reactively by
-- RegisterPartitionManager in response to SchemaChangeEvent -- not part of this baseline, since
-- their names/count depend on runtime admin actions, not compile-time knowledge.

CREATE TABLE register_item (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id         UUID NOT NULL,
    item_type_id    UUID NOT NULL REFERENCES schema_item(id),
    state           TEXT NOT NULL,
    transaction_id  UUID,
    commit_id       UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE register_link (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    link_id             UUID NOT NULL,
    link_definition_id  UUID NOT NULL REFERENCES schema_link(id),
    state               TEXT NOT NULL,
    transaction_id      UUID,
    commit_id           UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX register_link_link_id_idx ON register_link (link_id);

-- Instance-level marker assignment (item -> marker). Markers only ever apply to items, never to
-- links. A join table, not a column on register_item: those rows aren't updated in place
-- (commitItem() stages a whole new row per mutation), so a join table gets the same one-line FK
-- repoint register_item_link_perspective already uses on commit.
CREATE TABLE register_item_marker (
    id               UUID PRIMARY KEY DEFAULT uuidv7(),
    register_item_id UUID NOT NULL REFERENCES register_item(id) ON DELETE CASCADE,
    marker_id        UUID NOT NULL REFERENCES authorization_marker(id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (register_item_id, marker_id)
);
CREATE INDEX idx_register_item_marker_marker ON register_item_marker(marker_id);

CREATE TABLE register_item_link_perspective (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    register_link_id    UUID NOT NULL REFERENCES register_link(id) ON DELETE CASCADE,
    perspective_id      UUID NOT NULL REFERENCES schema_entity_link_perspective(id),
    register_item_id    UUID NOT NULL REFERENCES register_item(id)
);

CREATE TABLE register_binary_property (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    register_item_id UUID NOT NULL REFERENCES register_item(id) ON DELETE CASCADE,
    property_id      UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
    binary_id        UUID NOT NULL
);
CREATE INDEX register_binary_property_item_idx ON register_binary_property (register_item_id);

-- === binary_content (BinaryInitializer) ===

-- Uniqueness is a compound (sha256, md5, length) key, not sha256 alone: the storage layer
-- (BlockDeviceBinaryStorageAdapter.permanentRelativePath) already places a file on disk by sha256
-- *and* md5 together, so the DB has to agree or the two layers can disagree about what "the same
-- content" means -- a sha256 match with a differing md5/length (only plausible from an internal
-- hashing bug, never a practical attack on either hash) would otherwise let the DB silently conflate
-- two uploads under one id while storage kept them as two separate files, permanently orphaning one.
-- length earns its place in the key for the same reason md5 does: it's computed via an entirely
-- independent code path (a filesystem stat after the write closes, not the running digest during the
-- write), so it catches our own hashing bugs, which are far likelier than an actual hash break.
--
-- metadata mirrors sha256/md5/length/mime_type (see BinaryPartitionManagerImpl.insert) so every
-- intrinsic fact about a binary is reachable through one uniform path-resolution mechanism, the same
-- one embedded EXIF/IPTC data will use later -- while the real columns stay authoritative for the
-- typed, functional reads that want them directly (openReader, the ETag header, Content-Length).
-- This duplication is safe because these four values are write-once, computed synchronously during
-- upload and written in the same INSERT as the row itself; binary_content rows are never updated
-- after that. See docs/ntrloc-dynamic-properties-design-notes.md section 8.
CREATE TABLE binary_content (
    id         UUID PRIMARY KEY DEFAULT uuidv7(),
    sha256     TEXT NOT NULL,
    md5        TEXT NOT NULL,
    mime_type  TEXT,
    length     BIGINT NOT NULL,
    metadata   JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (sha256, md5, length)
);

-- === ledger_entry (LedgerInitializer) ===

CREATE TABLE ledger_entry (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sequence_number   BIGINT GENERATED ALWAYS AS IDENTITY,
    target_type       TEXT NOT NULL,
    target_id         UUID NOT NULL,
    entry_type        TEXT NOT NULL,
    payload           JSONB NOT NULL,
    transaction_id    UUID NOT NULL,
    state             TEXT NOT NULL,
    commit_id         UUID,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_external_id TEXT
);
CREATE INDEX ledger_entry_target_idx ON ledger_entry (target_type, target_id, sequence_number);
CREATE INDEX ledger_entry_transaction_idx ON ledger_entry (transaction_id);

-- === process_* (ProcessPersistenceInitializer) ===
-- ntrloc's own custom Flowable persistence layer (shadow tables for the BPMN engine), not
-- Flowable's own ACT_* tables -- those are managed by Flowable's own internal schema versioning,
-- untouched by this migration. No FK constraints by design: these ids are loosely correlated with
-- Flowable's in-memory/native identifiers, not enforced referentially at the DB level.

CREATE TABLE process_deployment (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(255),
    deployment_time TIMESTAMPTZ
);

CREATE TABLE process_resource (
    id            VARCHAR(64) PRIMARY KEY,
    deployment_id VARCHAR(64) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    bytes         BYTEA NOT NULL
);

CREATE TABLE process_definition (
    id            VARCHAR(64) PRIMARY KEY,
    deployment_id VARCHAR(64),
    process_key   VARCHAR(255) NOT NULL,
    name          VARCHAR(255),
    version       INT NOT NULL,
    resource_name VARCHAR(255),
    revision      INT NOT NULL DEFAULT 1
);

CREATE TABLE process_execution (
    id                       VARCHAR(64) PRIMARY KEY,
    revision                 INT NOT NULL DEFAULT 1,
    process_instance_id      VARCHAR(64),
    parent_id                VARCHAR(64),
    root_process_instance_id VARCHAR(64),
    super_execution_id       VARCHAR(64),
    process_definition_id    VARCHAR(64),
    business_key             VARCHAR(255),
    activity_id              VARCHAR(255),
    is_active                BOOLEAN NOT NULL DEFAULT TRUE,
    is_scope                 BOOLEAN NOT NULL DEFAULT FALSE,
    is_concurrent            BOOLEAN NOT NULL DEFAULT FALSE,
    is_ended                 BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX process_execution_proc_inst_idx ON process_execution (process_instance_id);
CREATE INDEX process_execution_parent_idx ON process_execution (parent_id);

CREATE TABLE process_variable (
    id                  VARCHAR(64) PRIMARY KEY,
    execution_id        VARCHAR(64),
    process_instance_id VARCHAR(64),
    name                VARCHAR(255) NOT NULL,
    type_name           VARCHAR(64),
    text_value          TEXT,
    long_value          BIGINT,
    double_value        DOUBLE PRECISION
);
CREATE INDEX process_variable_execution_idx ON process_variable (execution_id);

CREATE TABLE process_task (
    id                     VARCHAR(64) PRIMARY KEY,
    name                   VARCHAR(255),
    assignee               VARCHAR(255),
    create_time            TIMESTAMPTZ,
    execution_id           VARCHAR(64),
    process_instance_id    VARCHAR(64),
    process_definition_id  VARCHAR(64),
    task_definition_key    VARCHAR(255)
);
CREATE INDEX process_task_execution_idx ON process_task (execution_id);
CREATE INDEX process_task_proc_inst_idx ON process_task (process_instance_id);

CREATE TABLE process_task_identitylink (
    id                  VARCHAR(64) PRIMARY KEY,
    task_id             VARCHAR(64),
    process_instance_id VARCHAR(64),
    type                VARCHAR(64) NOT NULL,
    user_id             VARCHAR(255),
    group_id            VARCHAR(255)
);
CREATE INDEX process_task_identitylink_task_idx ON process_task_identitylink (task_id);
CREATE INDEX process_task_identitylink_proc_inst_idx ON process_task_identitylink (process_instance_id);

CREATE TABLE process_job (
    id                        VARCHAR(64) PRIMARY KEY,
    revision                  INT NOT NULL DEFAULT 1,
    job_kind                  VARCHAR(32) NOT NULL,
    category                  VARCHAR(255),
    job_type                  VARCHAR(255),
    job_handler_type          VARCHAR(255),
    job_handler_configuration TEXT,
    lock_owner                VARCHAR(255),
    lock_expiration_time      TIMESTAMPTZ,
    is_exclusive              BOOLEAN NOT NULL DEFAULT TRUE,
    execution_id              VARCHAR(64),
    process_instance_id       VARCHAR(64),
    process_definition_id     VARCHAR(64),
    element_id                VARCHAR(255),
    element_name              VARCHAR(255),
    scope_id                  VARCHAR(255),
    sub_scope_id               VARCHAR(255),
    scope_type                 VARCHAR(255),
    scope_definition_id        VARCHAR(255),
    correlation_id              VARCHAR(255),
    retries                    INT NOT NULL DEFAULT 0,
    exception_message           TEXT,
    due_date                    TIMESTAMPTZ,
    repeat_cycle                VARCHAR(255),
    end_date                    TIMESTAMPTZ,
    max_iterations               INT,
    create_time                  TIMESTAMPTZ
);
CREATE INDEX process_job_kind_lock_idx ON process_job (job_kind, lock_expiration_time);
CREATE INDEX process_job_execution_idx ON process_job (execution_id);
CREATE INDEX process_job_proc_inst_idx ON process_job (process_instance_id);
CREATE INDEX process_job_correlation_idx ON process_job (correlation_id);

CREATE TABLE process_event_subscription (
    id                     VARCHAR(64) PRIMARY KEY,
    revision               INT NOT NULL DEFAULT 1,
    event_type             VARCHAR(64) NOT NULL,
    event_name             VARCHAR(255),
    execution_id           VARCHAR(64),
    process_instance_id    VARCHAR(64),
    activity_id            VARCHAR(255),
    configuration          VARCHAR(255),
    created                TIMESTAMPTZ NOT NULL,
    process_definition_id  VARCHAR(64)
);
CREATE INDEX process_event_sub_type_name_idx ON process_event_subscription (event_type, event_name);
CREATE INDEX process_event_sub_execution_idx ON process_event_subscription (execution_id);
CREATE INDEX process_event_sub_proc_inst_idx ON process_event_subscription (process_instance_id);

CREATE TABLE process_activity_instance (
    id                         VARCHAR(64) PRIMARY KEY,
    revision                   INT NOT NULL DEFAULT 1,
    process_instance_id        VARCHAR(64) NOT NULL,
    process_definition_id      VARCHAR(64) NOT NULL,
    execution_id               VARCHAR(64) NOT NULL,
    activity_id                VARCHAR(255) NOT NULL,
    activity_name               VARCHAR(255),
    activity_type               VARCHAR(255) NOT NULL,
    task_id                     VARCHAR(64),
    assignee                    VARCHAR(255),
    completed_by                VARCHAR(255),
    start_time                  TIMESTAMPTZ NOT NULL,
    end_time                    TIMESTAMPTZ,
    duration_millis              BIGINT,
    transaction_order            INT,
    delete_reason                VARCHAR(4000),
    called_process_instance_id   VARCHAR(64)
);
CREATE INDEX process_activity_inst_proc_inst_idx ON process_activity_instance (process_instance_id);
CREATE INDEX process_activity_inst_exec_act_idx ON process_activity_instance (execution_id, activity_id);

CREATE TABLE process_property (
    name     VARCHAR(255) PRIMARY KEY,
    revision INT NOT NULL DEFAULT 1,
    value    TEXT
);

CREATE TABLE process_definition_info (
    id                    VARCHAR(64) PRIMARY KEY,
    revision              INT NOT NULL DEFAULT 1,
    process_definition_id VARCHAR(64) NOT NULL,
    info_json_id          VARCHAR(64)
);
CREATE INDEX process_def_info_proc_def_idx ON process_definition_info (process_definition_id);

-- === decision_* (DecisionPersistenceInitializer) ===
-- ntrloc's own custom Flowable DMN persistence layer, same rationale as process_* above.

CREATE TABLE decision_deployment (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(255),
    deployment_time TIMESTAMPTZ
);

CREATE TABLE decision_resource (
    id            VARCHAR(64) PRIMARY KEY,
    deployment_id VARCHAR(64) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    bytes         BYTEA NOT NULL
);

CREATE TABLE decision_definition (
    id            VARCHAR(64) PRIMARY KEY,
    deployment_id VARCHAR(64),
    decision_key  VARCHAR(255) NOT NULL,
    name          VARCHAR(255),
    version       INT NOT NULL,
    resource_name VARCHAR(255)
);

CREATE TABLE decision_historic_execution (
    id                     VARCHAR(64) PRIMARY KEY,
    decision_definition_id VARCHAR(64),
    deployment_id          VARCHAR(64),
    start_time             TIMESTAMPTZ,
    end_time               TIMESTAMPTZ,
    instance_id            VARCHAR(64),
    execution_id           VARCHAR(64),
    activity_id            VARCHAR(255),
    scope_type             VARCHAR(255),
    failed                 BOOLEAN NOT NULL DEFAULT FALSE,
    tenant_id              VARCHAR(255),
    execution_json         TEXT
);
CREATE INDEX decision_hist_exec_def_idx ON decision_historic_execution (decision_definition_id);
CREATE INDEX decision_hist_exec_inst_idx ON decision_historic_execution (instance_id);
