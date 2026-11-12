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
| 1.8a | **Modified dots.** In a draft, a dot in the draft colour marks the way to each change: Access in the sidebar, the Users/Groups tab, the user or group, its Permission grants tab, the item type card, the marker row, and the pill itself. `GET /api/drafts/{id}/changes` (admins) lists the draft's grant changes that still differ from live as it is now, so a change set back, or one live has caught up with, loses its dot. Later phases add their own families of change to the same endpoint and their own trail. | Follow the dots to a changed pill; undo it and the dots go |
| 1.9 | **Close the phase.** Benchmark against the baseline (`20261110-220149-phase1-close`: no regression past +15%; largest +12.9%, an admin's single item); what the slice taught us is notes §12. | Phase review |

## Phase 1R — Keeping a draft in step with live (reconciliation)

**Designed 2026-11-11** (notes §13). A draft floats on live: it always shows live as it is now plus its
own changes, but never silently. Each draft change records the live revisions of what it depends on; the
draft (never live) compares them with live; anything that moved, was deleted, or no longer fits becomes a
reconciliation item an admin resolves one at a time; and a draft can't be applied while it's internally
inconsistent or has anything left to reconcile. Built for grants first, before Phase 2, since groups
build on it. Live's only part is bumping revisions in its own writes; it never consults drafts.

| Step | What | Checkpoint |
|---|---|---|
| R.1 | **Isolation made mechanical.** ArchUnit: no event listeners in the draft package, and cluster topics only through `DraftTopics` (every name under `draft.`). Draft stores share live's connection pool; no separate database role (notes §13.1). *Done 2026-11-11: each rule shown failing on a deliberate violation.* | Rules in the build |
| R.2 | **Live revisions** for what Phase 1 drafts depend on: users and groups, per element (notes §13.3). A group's revision covers its name, parent, subgroups and members; a user's, their details and groups. A relationship change bumps every element on either side (moving G from A to P bumps G, A and P). Grant targets count by existence only (their revisions come with Phase 3's schema versions). Also, so live stays consistent on its own (notes §13.2): groups form a tree (one parent each; only `everyone` has none), enforced by the database; deleting a group with subgroups is refused unless forced, which deletes them too; deleting a user or group deletes their grants (new `UserService`); and a schema delete's cascaded grants leave the grant view on every node. React Access UI: Delete on a group (not `everyone`, not in a draft), listing the subgroups that go with it. *Done 2026-11-11.* | Live writes bump exactly what they change; benchmark shows no regression past +15% |
| R.3 | **Draft changes record their dependencies.** Each grant override stores the live revisions it was based on when written. *Done 2026-11-11: principal revision, the path as a list, and the inherited value (notes §13.3).* | Overrides carry their bases |
| R.4 | **Detection.** The draft compares each override's bases with live, on read and at apply, and lists reconciliation items: moved underneath, orphaned, no longer valid (conflicting can't happen for grants). Orphans stop being dropped silently at apply. Admin-only API next to `/changes`. *Done 2026-11-11.* | Integration tests: a live group move, a deleted marker, a deleted group each produce the right item |
| R.5 | **Reconcile UI.** A second kind of mark, distinct from the modified dot, on the same trail; a Reconcile view listing the items; per item: keep (after review), take live, adjust, or discard. Resolving moves the override's base up to live as it is now. *Done 2026-11-11: "Reconcile…" in the draft menu, a red mark replacing the amber dot along the trail and beside the draft's name.* | Selenium: follow the mark from the sidebar, resolve each kind of item |
| R.6 | **Apply gate.** Apply refuses while any item is unresolved, re-checking inside its transaction; the close dialog says why and links to the Reconcile view. *Done 2026-11-11.* | Apply blocked, then allowed once reconciled |
| R.7 | **Live changelog** (notes §13.6). Live appends an entry per group, membership and grant change, in the same transaction: kind, element, before and after, who, when. Retention setting (default: keep everything) with pruning that keeps whatever an open draft still needs. *Done 2026-11-11: user and group writes now all go through `UserService` (ArchUnit); deletions of users, groups and markers are one entry each; schema-cascade grant removals not yet logged.* | Every live write to these leaves exactly one entry; benchmark |
| R.7b | **Draft changelog** (notes §13.6; *done 2026-11-11*): who changed what in a draft, and when; same entry shape, its own table, written only by draft stores. At apply, live's entries say they came from the draft. | Each draft write leaves one entry; apply's live entries name the draft |
| R.8 | **Push to browsers.** After such a change commits, live sends an "access changed" event on the existing server-sent-events channel; any tab showing Access reads again, and a draft tab also its changes and notices. The 30-second poll goes. *Done 2026-11-11: sent once per transaction after commit, admins only; a failure to send is logged, never thrown into the committed change. The event stream now opens at once (an initial comment), not at the first 30-second heartbeat. Gap: a second admin in the same draft sees the other's draft changes on their next read, not pushed.* | A second tab updates without a reload |
| R.9 | **Informational notices.** A draft records its starting position in the log; `/changes` adds the entries since, latest per element, minus those acknowledged. `POST .../acknowledge` per entry, admins only. *Done 2026-11-11: `/changes` carries `notices` (latest per element, with a count); `POST /api/drafts/{id}/acknowledge`, recorded in the draft's log.* | Integration tests: move, rename, membership, grant; acknowledge one of two |
| R.10 | **Notices in the UI.** In a group's or user's details, each notice in words with Acknowledge; a blue mark on the trail (red still wins); a bar under the header ("3 live changes to review · 1 change needs reconciling [Review…]"); one review dialog with both lists and Show links. *Done 2026-11-11: blue marks; the strongest mark wins (reconcile, then live change, then draft change); a "Changed in live" panel in a user's or group's details; the bar under the header; one Review dialog (menu "Review…") with both lists and Show links.* | Selenium: move a group in live, see it in the draft, acknowledge |
| R.11 | **Revisions replaced by the changelog.** Required reconciliation asks whether any entry touched what a change depends on since its recorded position; the revision columns go. *Moved to Phase 2 (step 2.1), 2026-11-11.* | The R.3-R.6 tests pass unchanged in meaning |
| R.12 | **Operations screen**, first item: changelog retention. *Moved to Phase 2 (step 2.2), 2026-11-11.* | Set and honoured |

**Impersonation (raised 2026-11-10, phase not chosen).** An admin seeing the system -- live or a draft
-- as a given user. Needed in one of the phases below; the React admin UI is admin-only, so until then
a non-admin's view of a draft is checked through the API.

## Phase 2 — Groups in drafts

**Planned 2026-11-11.** A draft can change groups and memberships, as Phase 1 lets it change grants, with
the conflicts that brings (notes §13.4.1). It opens with what Phase 1R left to do. (A step for "accept or
put back" live changes was dropped, 2026-11-11: an informational item is only ever acknowledged; choices
belong to conflicts.)

| Step | What | Checkpoint |
|---|---|---|
| 2.1 | **Revisions replaced by the changelog** (was R.11). Required reconciliation asks whether any changelog entry touched what a change depends on since its recorded position; the revision columns go. Expected to close the gap where a member joining a group's subgroup isn't flagged. *Done 2026-11-11: counts of relevant entries since the draft started, per role (principal, groups above, subgroups); renames don't count. Both gaps closed; revision columns removed.* | The R.3-R.6 tests pass unchanged in meaning |
| 2.2 | **Operations screen** (was R.12), first item: changelog retention. *Done 2026-11-11: an Operations entry in the sidebar; a Changelog card (days to keep, blank for everything; entries held and since when); saving prunes at once. `GET`/`PUT /api/admin/operations/changelog`, admins only.* | Set and honoured |
| 2.3 | **Groundwork.** Groups out of `UserStore` behind `UserService` (begun in Phase 1R); memberships and admin flags held in memory, replaced after a change commits and announced across the cluster, like `GrantsView`, so `getUserGrants` reads them at no per-request query. Cache `UserGrantsView` per user only if the benchmark shows `forUser` matters. *Done 2026-11-11: groups and memberships split into `UserGroupStore` (writes still only through `UserService`, ArchUnit). `UserAccessCache`: each user's name, email, effective groups and admin flag, filled per user as asked for, cleared after any committed user, group or membership change, on every node (cluster topic). The principal resolver answers from it, so changes apply on the next request, a deleted user's session stops resolving, and token requests no longer query groups. A per-user `UserGrantsView` cache wasn't needed (benchmark).* | Benchmark |
| 2.4 | **`DraftGroupStore`.** Create, rename, move and delete groups, and add and remove members, in a draft, each recorded with what it started from; the draft's group view floats on live with those applied; apply writes through `UserService`. *Done 2026-11-11: tables `draft_group_change` (per group: created, deleted, name and parent with live's values as bases) and `draft_membership_change` (value and base, as grants). `DraftGroups`: live's groups and memberships now, with the draft's changes, cached per draft and rebuilt when either side changes (live's through `UserAccessCache.generation()`, a read). `DraftUserService` (only writer of `DraftGroupStore`) applies live's rules to the draft's groups and logs each change; API at `/api/drafts/{id}/admin/user-groups` (plus restore) and `/users/{id}/user-groups`; `/changes` gains `groups` and `memberships`. A group the draft creates keeps its id on apply; deleting it again removes it with its draft grants and memberships; a live group it deletes hides its grant changes, and restoring brings them back. A participant's draft grants use their draft groups. Apply: renames, new groups (parents first), moves (by draft depth), memberships, grants, then deletes (deepest first). Users and the admin flag stay live.* | Integration tests per kind of change |
| 2.5 | **Conflicts and invalid combinations** (notes §13.4.1): both sides changed the same thing (Keep draft change, or Preserve live change, or edit); the draft's change no longer valid (a deleted parent or group, a cycle, a duplicate name). Apply waits for all of them. *Done 2026-11-12: `GroupReconciler` (read-only) finds, per group change, a CONFLICT on name or parent (live moved off the base to a third value), GROUP_GONE, PARENT_GONE, CYCLE or NAME_TAKEN; for a group the draft deletes, CHANGED_IN_LIVE (live renamed, moved or changed its members, counted from the changelog as `deleted_touches`) and NEW_SUBGROUPS; for a membership added, USER_GONE or GROUP_GONE. Each reason gives draft, live and base sides, its choices and the live changes behind it, which leave the notices list; `POST /reconcile` takes `groupId` + `field` (+ `userId` for memberships) with KEEP, USE_LIVE, ABANDON or DELETE_SUBGROUPS; editing settles too, and whatever settles a reason acknowledges its notices. Apply counts group items with grants and names both. The group name constraint is deferrable: apply defers it, so names can be swapped or a deleted group's reused.* | Integration tests: each case in §13.4.1 |
| 2.6 | **The UI:** group editing in a draft, and conflicts in place, both sides side by side, on the trail of marks. *Done 2026-11-12: groups, memberships and a user's groups are edited in a draft through the draft's API (users stay live). Amber and red marks for group and membership changes. Each item shows as close as possible to what it's about: name items under Name, parent items under Parent group, delete items on the deleted group, orphaned members in Members; each with both sides, the live change behind it (who, when) and its choices, or a hint to edit. Groups the draft deletes stay in the tree, struck through, selected after deleting, with Restore (`GET .../user-groups/deleted`). The Review dialog lists group and membership items too, and stays open until Done; the applied summary counts group and membership changes.* | Selenium: the P1/P2/P3 example |

**Paused (2026-11-11):** further work on the marks themselves (the dots and their trail).

**Known gaps to keep in view:** grants removed by a schema delete's cascade aren't logged until schema changes are (Phase 3); a second
admin in the same draft sees the other's changes only on their next read; the legacy `/admin` UI isn't kept
in step.

## Later phases (outline)

| Phase | Groundwork | Draft work |
|---|---|---|
| 2 — Groups | See "Phase 2 — Groups in drafts" above | See above |
| 3 — Schema | Split `SchemaStore` into per-element stores (notes §8.7); fix controlled-list column ownership | Schema drafts, after the schema-versioning design; revisit "refuse live changes where a draft reaches" against notes §13.1 and §13.5 |
| 4 — Inventory | — | Try-out items in the draft ledger and register; the physical form (notes §7.3); a draft's consequences for existing items |
| 5 — Processes | Outbound integrations as definitions (notes §10) | Preview process instances; outbound sending suppressed in drafts |
