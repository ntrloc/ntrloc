# ntrloc Design Summary: State Machines for Items

## Topics: Motivation, Schema Representation, _state System Property, Transitions, Guards, Authorization, Process Contracts

This is a checkpoint of an in-progress design conversation, not a finished spec. Revise/refine in
place as the thinking develops further.

---

## 1. Why State Machines Belong in ntrloc

Many of the most common application categories built on ntrloc — issue trackers, helpdesk systems,
approval workflows, publishing pipelines — require items to move through a defined lifecycle. Without
first-class platform support, every consuming team hand-rolls its own ad hoc status field, its own
transition logic, and its own process hooks. A platform should own this once, correctly.

### Concrete driving use cases

- **Issue tracking (JIRA-style):** subtasks move through `OPEN → IN PROGRESS → CLOSED`, with
  different users authorized to make different transitions.
- **Helpdesk (ServiceNow-style):** tickets resolve through `OPEN → IN PROGRESS → CLOSED/UNNEEDED`
  or `CLOSED/COMPLETE`, with the submitter able to close as unneeded but only a help desk member
  able to advance to in-progress.

---

## 2. Schema-Level Representation

State machine definitions live at the **item definition level** (and possibly trait definition level)
in the schema — consistent with how properties and links are defined.

Each item type that has a state machine defines:

1. **A set of states** — named states the item can be in.
2. **A set of transitions** — each transition is a `{ from, to, name, processId? }` record (see Section 4).
3. **Entry/exit processes** on individual states (see Section 4).
4. **Guard conditions** on transitions (see Section 5).
5. **An optional initialization process** — resolves which initial state to enter at item creation time (see below).

### Initial states and initialization

An item type may designate one or more states as initial (`is_initial`). The rules at creation time:

- **One initial state, no init process** — that state is entered automatically.
- **Multiple initial states, init process present** — the init process runs and returns the name of
  the starting state. Entry for the chosen state fires normally once the process resolves.
- **Multiple initial states, no init process** — validation error at schema definition time.

The init process is a **typed process** (see Section 9) with a well-defined output contract: it must
return a single state name. This is enforced by the back-end at deploy/assignment time.

### Proposed tables

```sql
-- schema_item gains one new column:
init_process_id  TEXT   -- nullable; only meaningful when multiple initial states exist

CREATE TABLE schema_state (
    id                 UUID PRIMARY KEY DEFAULT uuidv7(),
    item_definition_id UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
    name               TEXT NOT NULL,
    description        TEXT,
    is_initial         BOOLEAN NOT NULL DEFAULT FALSE,
    entry_process_id   TEXT,
    exit_process_id    TEXT,
    UNIQUE (item_definition_id, name)
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
```

Notes:
- Terminal states are **implicit** — a state with no outgoing transitions in `schema_state_transition`.
- Transition names are display labels ("Approve", "Submit", "Reject"); the UUID is the stable key.
- Guard conditions are stored as JSONB, reusing the same predicate serialization format already used
  by projection filters.

---

## 3. The `_state` System Property

State is tracked in the ledger and reflected in the register as a **system-managed property**
named `_state`. It is:

- An `OBJECT`-typed property, not directly editable by users.
- The only way to advance `_state` is through the transition mechanism — this keeps the ledger
  honest and the audit trail intact.
- Atomic with any property changes triggered by entry/exit/transition processes, just like all
  other ledger/register interactions.

The `_state` object contains at minimum:

| Sub-field              | Purpose                                                     |
|------------------------|-------------------------------------------------------------|
| `current_state`        | The state the item is currently in                          |
| `current_transition`   | The transition in progress, if any (null otherwise)         |
| `transition_claimed_by`| The user/process that claimed the in-flight transition      |
| `candidates`           | Who is authorized to execute the *next* available transitions (see Section 6) |

Whether `_state` is exposed in projections/queries as a first-class field, or treated separately,
is an open question.

### In-flight transitions

When a transition involves a long-running process (user tasks, external calls, etc.) the item is
technically between two states. The `current_transition` and `transition_claimed_by` sub-fields
serve as a claim/lock on the item, preventing concurrent competing transitions. The process either
commits the new state (advancing `current_state`) or rolls back to the prior one.

---

## 4. Transitions

Each transition is defined as:

```
{ from: StateName, to: StateName, processId?: String }
```

- If `processId` is absent, the transition simply changes `current_state` — no process is spawned.
- A state may have an **exit process** and/or an **entry process** independent of the transition
  process. These fire even for "no processId" transitions.
- Execution order when all three are present: **exit → transition → entry**.
- At minimum, entry/exit/transition processes must be able to add, change, or remove properties
  on the item. Additional action types may be needed (TBD).

---

## 5. Guard Conditions

Transitions may declare an optional **guard condition** — a predicate that must be satisfied for
the transition to be available.

Guard conditions share the same predicate language as projection filters, since they express the
same kinds of constraints:

- item has a property / property value
- item has a link
- item has a link with a property / property value
- item has a linked item with a property / property value
- etc.

Because guards are predicates against the item's current state, they can be evaluated **before**
the user attempts a transition (to drive UI — e.g. disabling or hiding unavailable transitions)
**and** re-evaluated inside the process as the authoritative gate. Both evaluations run the same
predicate; no separate mechanism is needed for each.

---

## 6. Transition Authorization

Rather than a static ACL on each transition, authorization is determined **dynamically at state
entry** via the `candidates` field of `_state`.

When an item enters a state, the entry process (or a built-in platform step) can inspect the
item's properties, links, and related items and populate `candidates` — a list of users and/or
groups who are authorized to execute each of the transitions available from that state.

This is more flexible than a static ACL:

- The submitter of a ticket can close it as unneeded, but only a help desk group member can move
  it to in-progress — determined at the point the ticket enters `OPEN`, not at schema definition time.
- Authorization can depend on the item's data (e.g. which team owns the linked project).

Full design of the authorization model for transitions is deferred — the above establishes the
direction without committing to an implementation yet.

---

## 7. Cascading Transitions

Deferred. The likely implementation path when needed: a transition process step that queries
sibling items and optionally triggers a transition on a parent item — explicit and auditable,
rather than a framework-level cascade rule.

---

## 9. Process Contracts

Not all processes are open-ended. Some processes are invoked by the platform in a specific context
and must satisfy a **contract** — a typed category that declares what the process must return, what
task types it may contain, and when it runs.

This was recognized as an inevitable design concern when considering the state machine init process,
but applies more broadly: item lifecycle event processes, transition processes, and others predicted
to emerge will likely each have their own contracts. It is better to establish this concept early
than to retrofit it after many untyped process references exist in the schema.

### Contract dimensions

| Dimension | Description |
|---|---|
| **Output contract** | What the process must produce — e.g. a state name, a success/failure signal, or nothing |
| **Task type constraints** | Which BPMN task types are permitted or prohibited — e.g. user tasks are prohibited in init processes (waiting on a user decision during transaction init would stall the commit) |
| **Execution context** | Whether the process runs synchronously within a transaction or asynchronously after commit |

### Known process categories (so far)

| Category | Output | Prohibited tasks | Context |
|---|---|---|---|
| **State machine init** | State name (required) | User tasks | Synchronous |
| **State entry** | None | TBD | TBD |
| **State exit** | None | TBD | TBD |
| **State transition** | None (success/failure implicit) | TBD | TBD |

### Enforcement

- At **process deploy time**: the back-end validates that a process definition satisfies its declared
  category contract (correct output variables defined, no prohibited task types present).
- At **schema assignment time**: `init_process_id`, `entry_process_id`, etc. may only reference a
  process of the correct category — enforced by the back-end, guided by the admin UI (e.g. process
  pickers filtered by category).

This concept will likely warrant its own design document as more process categories are identified.

---

## 10. Open Questions

- Does `_state` appear in projections/queries as a first-class filterable/sortable field, or is it
  treated separately from user-defined properties?
- Exact shape of the `candidates` structure within `_state` (per-transition candidate lists vs. a
  flat list for all available transitions).
- Whether state machines can be defined on **traits** as well as item types, and how that
  interacts with an item that implements multiple traits each with their own state machine.
- What "actions" beyond property changes entry/exit/transition processes need to support.
