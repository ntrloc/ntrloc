-- Baseline: a faithful transcription of every table/index previously created ad hoc by the
-- *Initializer @PostConstruct classes (SchemaInitializer, RegisterInitializer, SecurityInitializer,
-- AuthorizationInitializer, BinaryInitializer, LedgerInitializer, ProcessGroupInitializer,
-- ProcessPersistenceInitializer, DecisionPersistenceInitializer -- all now deleted, Flyway owns
-- this DDL from here on). Statement order follows the FK dependency graph, not source-file order.
--
-- security_user.email is folded directly into its CREATE TABLE (SecurityInitializer used to patch
-- it on via a separate ALTER TABLE ... ADD COLUMN IF NOT EXISTS, evidence of the exact problem
-- Flyway now replaces -- this baseline represents current target shape, not that history).

-- === schema_* (SchemaInitializer) ===

CREATE TABLE schema_item (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    name            TEXT NOT NULL UNIQUE,
    description     TEXT,
    init_process_id TEXT
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

-- name is intentionally not unique here -- a property's name only needs to be unique within
-- whichever single item/trait/link type it's associated with (enforced in SchemaManager, since the
-- association is a separate join table, not a column here). Two different types legitimately have
-- their own distinct property row (different id) that happens to share a name, e.g. Product.name
-- and Contributor.name.
CREATE TABLE schema_property (
    id                 UUID PRIMARY KEY DEFAULT uuidv7(),
    name               TEXT NOT NULL,
    description        TEXT,
    type               TEXT NOT NULL,
    cardinality        TEXT NOT NULL,
    usage              TEXT NOT NULL,
    controlled_list_id UUID REFERENCES schema_controlled_list(id) ON DELETE SET NULL
);

CREATE TABLE schema_item_property (
    item_definition_id UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
    property_id        UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
    PRIMARY KEY (item_definition_id, property_id)
);

CREATE TABLE schema_trait_property (
    trait_id    UUID NOT NULL REFERENCES schema_trait(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
    PRIMARY KEY (trait_id, property_id)
);

CREATE TABLE schema_item_trait (
    item_id  UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
    trait_id UUID NOT NULL REFERENCES schema_trait(id) ON DELETE CASCADE,
    PRIMARY KEY (item_id, trait_id)
);

CREATE TABLE schema_link_property (
    link_definition_id UUID NOT NULL REFERENCES schema_link(id) ON DELETE CASCADE,
    property_id        UUID NOT NULL REFERENCES schema_property(id) ON DELETE CASCADE,
    PRIMARY KEY (link_definition_id, property_id)
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

CREATE TABLE schema_state (
    id               UUID PRIMARY KEY DEFAULT uuidv7(),
    state_machine_id UUID NOT NULL REFERENCES schema_state_machine(id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    description      TEXT,
    is_initial       BOOLEAN NOT NULL DEFAULT FALSE,
    entry_process_id TEXT,
    exit_process_id  TEXT,
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
-- UNIQUE(entity_id, link_definition_id) is what 1.0.0 actually shipped with -- this baseline is a
-- faithful record of that, bug included, not a retroactively-cleaned-up version. It's removed for
-- real by V1_0_1_1__drop_stale_perspective_unique_constraint.sql (see that file for why: it made
-- same-type/same-trait self-links impossible).
CREATE TABLE schema_entity_link_perspective (
    id                  UUID PRIMARY KEY DEFAULT uuidv7(),
    entity_id           UUID NOT NULL,
    link_definition_id  UUID NOT NULL REFERENCES schema_link(id) ON DELETE CASCADE,
    name                TEXT NOT NULL,
    description         TEXT,
    minimum_cardinality INT NOT NULL CHECK (minimum_cardinality >= 0),
    maximum_cardinality INT CHECK (maximum_cardinality IS NULL OR maximum_cardinality >= minimum_cardinality),
    UNIQUE (entity_id, link_definition_id)
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

CREATE TABLE security_group (
    id   UUID PRIMARY KEY DEFAULT uuidv7(),
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE security_group_member (
    user_id  UUID NOT NULL REFERENCES security_user(id)  ON DELETE CASCADE,
    group_id UUID NOT NULL REFERENCES security_group(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, group_id)
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

CREATE TABLE authorization_marker (
    id          UUID PRIMARY KEY DEFAULT uuidv7(),
    name        TEXT NOT NULL UNIQUE,
    description TEXT
);

-- Static, admin-curated assignment table. This exact shape is expected to later become "rule
-- engine output" without structural change -- no logic lives here.
CREATE TABLE authorization_item_type_marker (
    item_type_id UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
    marker_id    UUID NOT NULL REFERENCES authorization_marker(id) ON DELETE CASCADE,
    PRIMARY KEY (item_type_id, marker_id)
);

-- Row-per-operation (not flags-per-row): keeps the door open for new primitives via a
-- CHECK-constraint edit rather than a schema migration, and avoids sparse columns for primitives
-- that don't apply to a given marker's kind.
CREATE TABLE authorization_grant (
    id             UUID PRIMARY KEY DEFAULT uuidv7(),
    marker_id      UUID NOT NULL REFERENCES authorization_marker(id) ON DELETE CASCADE,
    principal_type TEXT NOT NULL CHECK (principal_type IN ('USER', 'GROUP')),
    principal_id   UUID NOT NULL,
    operation      TEXT NOT NULL CHECK (operation IN (
        'item:create','item:read','item:delete',
        'property:read','property:write',
        'link:create','link:read','link:delete',
        'link_property:read','link_property:write',
        'binary:download','security:override','marker:apply','marker:remove'
    )),
    UNIQUE (marker_id, principal_type, principal_id, operation)
);

-- === process_group* (ProcessGroupInitializer) ===
-- Deliberately separate from security_group: a process-assignment group (who can pick up a User
-- Task) is a different concept from a permission group (what a set of users can do to schema/graph
-- data) even though both are "a group of users" -- coincidentally similar shape, unrelated
-- lifecycle and ownership.

CREATE TABLE process_group (
    id   UUID PRIMARY KEY DEFAULT uuidv7(),
    name TEXT NOT NULL UNIQUE
);

-- user_id references security_user directly -- individual identity is shared app-wide, it's
-- specifically the grouping mechanism that's kept separate from security_group.
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

CREATE TABLE register_item_link_perspective (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    register_link_id    UUID NOT NULL REFERENCES register_link(id) ON DELETE CASCADE,
    perspective_id      UUID NOT NULL REFERENCES schema_entity_link_perspective(id),
    register_item_id    UUID NOT NULL REFERENCES register_item(id)
);

CREATE TABLE register_binary_property (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    register_item_id UUID NOT NULL REFERENCES register_item(id) ON DELETE CASCADE,
    property_id      UUID NOT NULL REFERENCES schema_property(id),
    binary_id        UUID NOT NULL
);
CREATE INDEX register_binary_property_item_idx ON register_binary_property (register_item_id);

-- === binary_content (BinaryInitializer) ===

CREATE TABLE binary_content (
    id         UUID PRIMARY KEY DEFAULT uuidv7(),
    sha256     TEXT NOT NULL UNIQUE,
    md5        TEXT NOT NULL,
    mime_type  TEXT,
    length     BIGINT NOT NULL,
    metadata   JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX binary_content_sha256_idx ON binary_content (sha256);

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
