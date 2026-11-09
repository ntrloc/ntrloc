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

| Step | What | Checkpoint |
|---|---|---|
| 1.1 | **Split `AccessStore`** (groundwork, no behaviour change). It holds three things today: grants (and `GrantSnapshot`), markers (`authorization_marker`, `getMarkerNames`), and item-marker lookups (`getMarkerIdsForItem`, which reads Inventory). Grants → `GrantStore`; markers → `MarkerStore`; item markers → the register's reader. | Access and marker screens unchanged |
| 1.2 | **`GrantService` as the only writer** (groundwork). Today `AccessAdminController`, `MarkerAdminController` and `DefaultUserGroupInitializer` write through `AccessStoreWriter` directly. Transactions move to the service; store writes require one. Turn on the ArchUnit rules for Access. | Grant editing unchanged |
| 1.3 | **Draft registry.** Live tables for drafts and participants (with each draft's base versions); a service for admins to start, list, invite to and discard drafts; admin endpoints. | Start a draft, invite a user, see it listed |
| 1.4 | **Draft mode.** The typed live-or-draft context; `/api/drafts/{draftId}/...` routing; only admins and that draft's participants get in. React UI: the switch into and out of draft mode, and the indicator. | Switch modes as an admin and as an invited non-admin; an uninvited user can't |
| 1.5 | **`DraftGrantStore` and the draft grant service.** The physical mechanism for draft grants (to decide at 1.0); a snapshot per draft; draft services built per draft by a factory, with the live service's logic. The Access screens edit the draft when in draft mode. Parity test: an empty draft yields exactly the live views. | Edit grants in a draft; live untouched |
| 1.6 | **Preview.** Permissions inside a draft come from the draft's grants, so projections in draft mode reflect them (the projection engine itself unchanged). Admins preview as a user; participants see their effective draft permissions. | Draft a grant, see a non-admin's results change in the draft and not in live |
| 1.7 | **Apply and discard.** Applying atomically, as its own operation outside both services; checking each draft's base versions and rebasing when live grants changed meanwhile; conflicts surfaced. | Apply a draft; discard one; apply after a conflicting live change |
| 1.8 | **Close the phase.** Benchmark against the baseline; update the notes with what the slice taught us. | Phase review |

## Later phases (outline)

| Phase | Groundwork | Draft work |
|---|---|---|
| 2 — Groups | Split groups out of `UserStore`; a service in front of it (19 dependents today) | `DraftGroupStore`, reusing Phase 1's machinery |
| 3 — Schema | Split `SchemaStore` into per-element stores (notes §8.7); fix controlled-list column ownership | Schema drafts, after the schema-versioning design |
| 4 — Inventory | — | Try-out items in the draft ledger and register; the physical form (notes §7.3); a draft's consequences for existing items |
| 5 — Processes | Outbound integrations as definitions (notes §10) | Preview process instances; outbound sending suppressed in drafts |
