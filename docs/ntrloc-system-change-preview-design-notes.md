# ntrloc — System Change Preview Design Notes

> **DRAFT — in discussion (2026-11-05).** Captures the idea as worked out so far. Mechanics are
> provisional; sandbox writes are paused mid-discussion (§8). Nothing here is implemented.

Related: `ntrloc-schema-evolution-design-notes.md` (versions, revisions, drafts, conformance debt),
`ntrloc-design-summary.md` (§6 Staging and Changesets — the earlier "sparse overlay" this reworks),
`ntrloc-mutations-ledger-summary.md` (UNCOMMITTED → COMMITTED), `ntrloc-acl-design-notes.md`.

---

## 1. Purpose: see the outcome, not a prediction

Not every change is correct, even when users ask for it or admins choose it. People request changes —
to permissions, schema, processes — without fully appreciating their impact. A description of a change
("this grant gives *Licensing* read on contract terms") is a prediction; the mistakes hide in the gap
between prediction and outcome ("logged in as Dana, I now see 4,000 contracts, not the 40 I expected").

Without a way to see the outcome, it's first seen live, in production, by everyone.

**The system change preview is the mechanism through which admins and partner users see the actual
outcome of a change to the system before it goes live — whatever the change is.** It's useful in
development, and more so in production, where it acts as a user acceptance review built into the system:
selected people use the changed system through the normal UI, and only then does it go live for
everyone.

Schema evolution is one user of the preview, not its reason for existing. A preview may hold only a
permissions change, only a process change, or any combination.

### 1.1 Answers, not storage

What the system *is*, to anyone using it, is the answers it gives: "what can Dana see?", "what
properties does Product have?", "which markers does this item carry?". Tables, overlays, and directories
are how it arrives at those answers.

- **The contract is the questions.** The live system and the preview answer the same questions through
  the same behavioral interface. Live answers from committed state; the preview answers as the system
  *would* once the change commits.
- **Fidelity is the preview's test.** Every answer the preview gives must be the answer the committed
  system gives after commit. That's testable: stage a change, record answers, commit, ask again,
  compare. A mismatch means the preview misled its reviewers — the one failure it can't have.
- **Commit is a promise** that the system's answers become what reviewers saw. However it works inside,
  it's correct exactly when fidelity holds.
- **Each part chooses how to back its answers.** Some need staged storage (promoted and transformed items
  must exist to be queried, §4). Others may need none: "what can Dana access?" can be answered by
  applying the preview's grant and membership changes to the committed answer.

### 1.2 Architecture: two stacks, one interface

How this is built matters a great deal. Done carelessly, preview checks spread through every code path
and the system becomes impossible to reason about. The shape:

```
Live I/O ──► Committed behavior ──► Committed structure
                    ▲  decorates             │ background sync (§4)
Preview I/O ──► Preview behavior ──► Preview structure (each part staged, or falls through)
```

- **Layers.** I/O (requests in, effects out); behavior (managers: entity, user, process, …); structure
  (users, groups, grants, schema, processes, ledger, register).
- **Committed behavior knows nothing about previews.** No flags, no checks, no preview branches in live
  code.
- **Preview behavior decorates committed behavior.** Everything preview-specific lives in the decorators:
  resolving overlays (preview first, otherwise delegate), routing writes to preview structure,
  suppressing outbound effects.
- **The preview stack is wired as a whole.** A decorated manager calling another manager reaches the
  decorated one, never the committed one.
- **The preview can't write to committed structure, by construction.** Its access to committed structure
  is read-only; only staged parts are writable. A missed decorator then fails loudly instead of
  corrupting live.
- **Preview I/O is a separate entry point**: where access is restricted to reviewers, the acting identity
  is established (self, or impersonated and audited — §7), and outbound effects stop.
- **Sync runs between the structures, not the behaviors**: live writes reach staged registers below both
  behavior layers.
- **One pattern everywhere.** Each manager may back its answers differently, but every one follows the
  same decorator shape — no one-off mechanisms per feature.
- **Fidelity tests are part of the design**, not an afterthought: each manager's decorator is tested by
  comparing its preview answers with the committed answers after commit.

---

## 2. What a preview can hold

The system's parts, and what depends on what:

```
User groups ── Users          Grants ──► Schema ◄── Processes
     ▲            ▲             │  │       ┊   ┊
     └────────────┴─────────────┘  │     Ledger Register
                                   └──► (schema)
```

(Grants reference users / groups and schema; processes depend on schema via declared dependencies; the
ledger and register hold data governed by the schema.)

| Part | In a preview? | Notes |
|---|---|---|
| **User groups, memberships** | Yes | Groups are sourced strictly within ntrloc, so they can be staged fully. |
| **Users** | No | Users stay live. |
| **Grants** | Yes | New, modified, and removed grants. |
| **Schema elements** | Yes | Revisions and new versions (schema evolution notes §5.1). |
| **Processes, decisions (BPMN / DMN)** | Yes | Deployed only in the preview. |
| **Data** | Yes, derived | Staged registers produced by promotion and transformers (§4, §5). |

Example: users are unhappy with some current permissions and ask for a new group, some new grants, and
some modified ones. The preview holds the new group and its members, the new and changed grants; partner
users see the result before anyone else is affected.

---

## 3. Core rules

- **One preview at a time.** The two-version constraint (design summary §6): one live world, at most one
  staged world.
- **Overlays, not copies.** Each staged part starts from live and holds only what the change alters;
  everything else is read from live. Storage is proportional to the change, not to the system.
- **Commit is one atomic switch across everything in the preview** — groups, memberships, grants,
  schema, processes, directories, ledger entries. If any part can't switch, none does. (A new group
  going live without its grants, or with the wrong ones, is exactly what this prevents.)
- **Discard is cheap.** Drop the staged parts; live was never touched.
- **Nothing leaves the preview.** No notifications, outbound pushes, integrations, real process effects,
  or live audit entries come from activity in the preview. Pipelines and state machines run — testing
  them is the point — but anything reaching the outside world is suppressed or simulated.
- **Live keeps moving.** Live changes reach the staged parts by background sync (§4). Live changes that
  would collide with what the preview alters are refused, offering "add to the preview" instead (as for
  schema drafts — schema evolution notes §5.4); the same rule for groups and grants is to be confirmed
  (§9).

---

## 4. Staged data: per-version registers and directories

- **A register table per item type and version**: `Register <Product, v1>`, `Register <Product, v2>`.
  (Today there's one table per item type — `register_item_<type>` — plus the shared `register_item`.)
- **A directory says which table holds each item's current row**: Product 1 → v2, Product 2 → v1, …
  - **Live directory** — today's `register_item`, with a version column.
  - **Staged directory** — the preview's. Staged projections read through it; live projections never do.
  - Live and staged projections run the same code; only the directory differs. A staged directory can
    point into registers that aren't committed (the `v2` table, until commit).
- **Promotion copies, it doesn't move.** Items conforming to the staged version are copied into its
  table; their `v1` rows stay, so live is untouched. Copying is also where data can be put into the new
  version's shape (an array of one becoming a single value).
- **Background sync from live.** Live writes during the preview:
  - a created or updated item that conforms to the staged version is copied (or refreshed) there, with a
    staged directory entry;
  - an item that stops conforming (or is deleted) loses its staged copy and entry;
  - non-conforming items are recorded in the staged directory at their live version — stragglers in the
    staged world, both pre-existing (Product 2) and new (Product 4).
- **Commit waits for the sync queue to drain** (or rechecks under a lock), so it remains a true switch.
  The staged view may lag live by a moment; that's fine for review.
- **Cleanup after commit** removes superseded rows (`v1` copies of promoted items).
- **Markers are staged too.** Staged rows get markers from the staged pipeline; staged projections and
  access in the preview use them.

Open: full staged directory (every item, simple queries, commit is a swap) vs. sparse (only items that
differ; `COALESCE(staged, live)`; cheaper on large types).

---

## 5. Transformers

A transformer turns an item that doesn't conform to the staged version into a form that does, so more
items arrive conforming. Reviewers see exactly what it did before commit — unlike a silent backfill.

- **Output must conform, or it doesn't count.** Each transformed copy is checked against the staged
  version. On success it gets a staged register row and directory entry; on failure, nothing is stored
  in the staged register — the item stays a straggler, with the **reason** recorded ("Product 4:
  `vendorContact` still missing after transformation"). Failures are grouped by gap, like the debt view.
  To see what a transformer produced for a failed item, re-run it on request.
- **Deterministic and repeatable.** Live writes keep arriving; a changed item is transformed again.
- **Derive, don't invent.** Values come from existing data — the item or its graph ("first of
  `genres`", "vendor from the linked Supplier", a unit conversion). Constants like "Unknown" are the
  fabricated values schema evolution §1 rejects; if ever allowed, they're marked as such.
- **Recorded in the ledger.** Transformed data goes live at commit, so each transformed item gets item
  ledger entries written as UNCOMMITTED with the preview's transaction id, attributed to the transformer
  and the preview; commit flips them with everything else.
- **Writes as the committing admin**, subject to their grants — not as the system.
- **Why an item fails, and what happens next:**

| Cause | What happens |
|---|---|
| A bug in the transformer | Fix it; it re-runs in the background. |
| A case nobody foresaw | Extend it; the failure revealed the gap. |
| Agreed to need a person | Recorded as **accepted debt**, with a reason; handled by the debt tools after commit. |

- **A person can fix an item live before commit**; background sync picks it up.
- Open: whether commit requires every failure to be fixed or accepted, or only shows them.

This revises a schema evolution decision of 2026-11-04 ("drafts never carry data fixes"): the reason
behind it — nothing unreviewed goes live — is met by the preview.

---

## 6. Groups and grants

- Staged groups, memberships, and grants overlay live ones (§3).
- A new group can exist only in the preview until commit.
- Grants orphaned by staged schema changes are shown in the preview and removed at commit, recorded
  (schema evolution notes §5.4).
- This revises the schema evolution decision that grants aren't part of drafts.

---

## 7. Viewing the preview

- **Partner users preview as themselves**: their live identity, with staged memberships, grants, schema,
  and markers — their own future, nothing more.
- **Admin impersonation** ("see the preview as Dana") shows one person's actual outcome. It's powerful —
  the admin sees what Dana would see, which may be more than the admin is granted — so it needs its own
  permission, is read-only, and is audited ("admin X viewed the preview as Dana").
- Only designated reviewers can enter the preview at all.

---

## 8. Writes in the preview (paused)

Captured so far, not settled:

- A write in the preview creates or changes only staged rows; e.g. a test Product exists only in the
  staged directory, marked not real. All preview writes are discarded at commit or discard.
- Three states are needed, not a real / not-real flag: *real*, *real but changed in the preview*,
  *preview-only*.
  - Background sync vs. a preview edit of a real item: overwrite (loses the reviewer's test) or skip and
    flag ("changed live since your edit", with a reset).
  - At commit, real items changed in the preview are recomputed from live, not switched.
- Testing a fix on a straggler creates a staged copy marked as changed in the preview.
- Preview writes need a ledger home — a separate preview ledger (or entries tagged with the preview id),
  deleted at commit or discard; not the existing UNCOMMITTED state, which means in flight.
- Links need staged link registers and the same states.

---

## 9. Open questions

1. Full vs. sparse staged directory (§4).
2. Commit requirement for transformer failures (§5).
3. Live changes to groups and grants while a preview is open: lock like schema, or sync with an overlap
   rule (§3).
4. Writes in the preview (§8).
5. Terminology: one concept for what admins compose (a draft) and what reviewers use (the preview).
6. Build order. A preview of groups and grants only needs no directories or version tables — just staged
   access state — and could come before schema versioning, delivering the permissions case on its own.
7. Reconcile with `ntrloc-design-summary.md` §6 (changesets, sparse overlay, approval workflows).
