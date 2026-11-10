# ntrloc Drafts — Plan of Attack

Status: draft plan, 2026-11-09. Built on `ntrloc-system-model-notes.md` (the concepts, vocabulary and
decisions this plan assumes). Expect revision as each phase opens.

## Approach

- **A thin vertical slice first.** Take the smallest draftable area through the whole draft design —
  registry, mode, draft store, draft service, preview, apply and discard, UI — before restructuring
  anything else. The slice tests the architecture on real code before it's applied everywhere.
- **Restructure a partition only when its drafts arrive.** No sweeping refactor ahead of need.
- **Decide just in time.** Each phase opens by having its stores answer the four questions (notes §7.2)
  and settling only the open questions that phase needs.
- **Every step leaves the system working and releasable.**

## Standing rules

- **Live projection performance is a hard constraint** (notes, top). The projection benchmark (opt-in,
  step 0.1) is run before and after every phase; a regression blocks the phase.
- **Review in the running system.** At each checkpoint below, the service runs (`runtimes/domain-1`), the
  React UI runs from the Vite dev server, and we review together through Chrome DevTools, as well as
  through tests.
- A plan is agreed before any step changes behaviour; groundwork steps change none.
- `mvn -B clean install` from the repo root (JDK 25) before a step is called done. The user commits.
- No faking in tests: real users, real authentication, real stores.

## Phase 0 — Guardrails

| Step | What | Checkpoint |
|---|---|---|
| 0.1 | **Projection benchmark.** A repeatable, timed run over a seeded dataset of realistic size: item and collection projections, with permission filtering, for an admin and a non-admin. **Off by default** — never part of an ordinary build or test run — and switched on explicitly (e.g. `-Dbenchmarks`, mirroring how `-DskipUiTests` works, but opt-in). Each run saves its results somewhere `mvn clean` doesn't wipe, and can compare against a chosen earlier run, so it serves this work and ad-hoc baseline checks later. Record a baseline. | Baseline numbers reviewed |
| 0.2 | **ArchUnit scaffolding** for the agreed vocabulary, switched on per partition as each is restructured: nothing on the live path refers to `Draft*`; each store has one writing service; store methods don't start transactions. | Rules in place, initially scoped to nothing |
| 0.3 | **Review setup.** The routine for checkpoints: rebuild, start `domain-1`, run the React UI from the Vite dev server (proxying to 9090), one tab only. | A first walkthrough of the current Access screens |

### Running the benchmark (built 2026-11-09)

```
mvn -pl domain-graph-starter test -Dbenchmarks -DskipUiTests -Dfrontend.skip=true [-Dbenchmarks.label=NAME]
```

`ProjectionBenchmark` (test sources, `org.ntrloc.graph.benchmark`) seeds 10,000 products, 5,000 parts and
100 suppliers through the HTTP APIs, times eight projection scenarios as an admin and as a non-admin who
sees a quarter of the data, checks the result counts, saves the run to `benchmark-results/projection/`
(gitignored) and compares it with the previous run (or `-Dbenchmarks.compareTo=LABEL`). Regression
threshold: +15% on the median (`-Dbenchmarks.threshold`); report only unless
`-Dbenchmarks.failOnRegression=true`. Other options are listed in the class comment. An ordinary build
never runs it. Baseline: `20261109-173100-baseline.json`; two identical runs agreed within about 5%
(worst case 11.5%).

## Phase 1 — First slice: grants (Access)

Why grants: row-shaped, schema-sized and already snapshotted; "try a grant, preview as a user" is
valuable on its own; and the slice reaches every hard part except schema versions and drafted items.

**Opens with:** the four questions for grants; the draft registry's shape; one open draft at first,
keyed by id throughout (notes §9.2); the URL scheme (notes §9.1).

**1.0 decided (2026-11-09):** grants per notes §7.2.1 (overrides with base values, one
`draft_grant_override` table, per-draft `GrantsView`, conflicts per permission on apply); registry per
notes §11. API: a new, coarser grants API -- `GET /api/admin/grants?principal=user:{id}`, `PUT
/api/admin/grants` (set or clear one permission) -- mirrored at `/api/drafts/{draftId}/admin/grants`
by its own controller. The ~30 fine-grained endpoints stay for the legacy `/admin` UI, live only, until
it's retired. Registry: `/api/drafts` (list, create), `/api/drafts/{id}/participants`,
`/api/drafts/{id}/discard`, `/api/drafts/{id}/apply`. Preview addressing is left to 1.6.

| Step | What | Checkpoint |
|---|---|---|
| 1.1 | **Split `AccessStore`** (groundwork, no behaviour change). It holds three things today: grants (and `GrantSnapshot`, renamed `GrantsView`), markers (`authorization_marker`, `getMarkerNames`), and item-marker lookups (`getMarkerIdsForItem`, which reads Inventory). Grants → `GrantStore`; markers → `MarkerStore`; item markers → the register's reader. | Access and marker screens unchanged |
| 1.2 | **`AccessService` as the only writer of grants and markers** (groundwork). Markers exist to scope grants, so one service owns `GrantStore` and `MarkerStore`; services don't mirror stores. The two admin controllers and both initializers now call it. Transactions are in the service; `GrantsView` rebuilds after commit. ArchUnit rules on for both stores. | Grant editing unchanged |
| 1.3 | **Draft registry.** Live tables for drafts and participants (with each draft's base versions); a service for admins to start, list, invite to and discard drafts; admin endpoints. | Start a draft, invite a user, see it listed |
| 1.4 | **Draft mode.** The typed context is `DraftScope`: a controller under `/api/drafts/{draftId}/...` takes one as a parameter, and it's only made for an admin or a participant of that open draft (anyone else gets a 404). Live controllers take none. First draft controller: the grants API, reading as live until 1.5. React UI: the switch and indicator, and the grants requests address the draft in draft mode. | Switch modes as an admin and as an invited non-admin; an uninvited user can't |
| 1.5 | **`DraftGrantStore` and `DraftAccessService`.** Overrides in `draft_grant_override`; `DraftGrantStore.getGrantsView(draftId)` is live's rows with the draft's overrides applied, cached per draft and rebuilt when live's view or the draft's overrides change. Store and service are singletons taking the draft; the permission-to-rows logic moved from `AccessService` into `GrantStore`. Both grants APIs check a permission's target exists (`GrantsApi.requireValid`). The Permission grants tab edits the draft in draft mode. Parity test: an empty draft yields exactly the live rows. | Edit grants in a draft; live untouched |
| 1.6a | **`UserGrantsView`** (groundwork, no behaviour change). One user's effective grants, taken from a `GrantsView` once per request, with the rules as its methods (`mayReadItemType`, `mayReadItem(markers)`, `mayWriteProperty(markers, property)`, ...). `AccessService.getUserGrants(user)` builds it from live; `PermissionService` and `RequestPermissionContext` are gone. The read services take a `UserGrantsView` for their checks. It takes the signed-in principal (for its id); for now its groups and admin flag are also the principal's, as resolved at sign-in. Current memberships come in Phase 2, from a cache (never a query per request). | Suite unchanged |
| 1.6b | **Preview.** `DraftAccessService.getUserGrants(scope, user)` builds a user's grants from the draft's `GrantsView`; draft projection and schema endpoints (`/api/drafts/{id}/projection/...`, `/api/drafts/{id}/schema`) use it, the projection engine unchanged. A participant sees the draft with their own draft permissions; items aren't drafted, so the UI's item writes are off in draft mode. Acting as another user is impersonation, out of scope here (see below). | Draft a grant, see a non-admin's results change in the draft and not in live |
| 1.7 | **Apply and discard.** `DraftService.apply` locks the draft and, in one transaction, writes each override to live through `AccessService` and closes the draft. A permission is on or off, so there's no value conflict: each override is reported as applied, already live, or dropped (its principal or target is gone). Writes to a draft hold it open (`FOR SHARE`), so none lands after it closes; closing evicts its cached view on every node. Overrides stay as the record. Apply and Discard in the draft switch. | Apply a draft; discard one; apply with a dropped grant |
| 1.8 | **Invitations.** UI for an admin to see, add and remove a draft's participants, in the draft switch ("Participants…": invite any non-admin, remove with one click). (The API came with 1.3.) | Invite and uninvite a user from the UI |
| 1.9 | **Close the phase.** Benchmark against the baseline (`20261110-220149-phase1-close`: no regression past +15%; largest +12.9%, an admin's single item); what the slice taught us is notes §12. | Phase review |

## Right after Phase 1 — Keeping a draft in sync with live (design conversation)

**Raised (2026-11-10), not settled.** Every aspect of live keeps changing while a draft is open, so we
need one mechanism that keeps a draft in step with live, where what "in sync" means depends on the data:

- **Grants** (Phase 1): live changes to permissions the draft didn't touch flow through on read;
  overrides keep their base value, so a later live change to one becomes a conflict at apply; an
  override whose target live deleted is left orphaned (no foreign key), and today only apply drops it.
- **Still to define** for groups, schema, inventory and processes: what live change a draft must react
  to, whether it reacts when the change happens or when the draft is applied, and what counts as a
  conflict, as stale, or as safe to absorb.

Inputs: notes §7.2.1 (grant overrides and base values), §9.2 (rebasing, and each draft recording what
it's based on). The outcome may revise Phase 1's orphan handling and step 1.7's conflicts.

**Impersonation (raised 2026-11-10, phase not chosen).** An admin seeing the system -- live or a draft
-- as a given user. Needed in one of the phases below; the React admin UI is admin-only, so until then
a non-admin's view of a draft is checked through the API.

## Later phases (outline)

| Phase | Groundwork | Draft work |
|---|---|---|
| 2 — Groups | Split groups out of `UserStore`; a service in front of it (19 dependents today). That service holds memberships (and admin flags) in memory, replaced after a change commits and announced across the cluster, like `GrantsView`; `getUserGrants` reads groups from it rather than from the principal, so changes apply on the next request at no per-request query. Cache `UserGrantsView` per user only if the benchmark shows `forUser` matters | `DraftGroupStore`, reusing Phase 1's machinery |
| 3 — Schema | Split `SchemaStore` into per-element stores (notes §8.7); fix controlled-list column ownership | Schema drafts, after the schema-versioning design |
| 4 — Inventory | — | Try-out items in the draft ledger and register; the physical form (notes §7.3); a draft's consequences for existing items |
| 5 — Processes | Outbound integrations as definitions (notes §10) | Preview process instances; outbound sending suppressed in drafts |
