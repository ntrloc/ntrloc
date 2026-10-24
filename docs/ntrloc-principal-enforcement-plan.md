# Principal Enforcement Plan

**Severity: SEV-0.** Status as of this writing: **Parts A through E are all done** (see each
section for its own changelog). The only remaining open item in this whole doc is question 2
(`ProcessService`'s own marker/permission bypass), explicitly deferred to later, separate work —
not blocking anything else here. This doc exists because the session writing it is near a usage
limit and may be interrupted mid-change — if you're reading this fresh, treat it as the
authoritative resume point, not the chat history.

**The migration prerequisite is done.** Part C was blocked on `docs/webflux-to-mvc-migration-plan.md`
(this app had to move off WebFlux before method security could work without the
`@EnableReactiveMethodSecurity`-hard-rejects-blocking-methods landmine described below) — that
migration is now fully complete, all six tiers including Tier 5 (the MCP transport, tracked
separately there). Parts C and D are also both now done; see their own sections for the full
changelogs.

A parallel summary of the same material (minus Part B, added after) was published as a claude.ai
Artifact during the session that produced this doc: look for one titled "Principal Enforcement" if
it's still reachable. This file is the durable copy; trust this one if they ever disagree.

## The problem, in one paragraph

`ProjectionService` (an MCP tool) hands every MCP caller a hardcoded admin principal:
`new ResolvedPrincipal(UUID.randomUUID(), "mcp-tool-caller", "MCP Tool Caller", null, Set.of(), true)`.
This isn't an unauthenticated-access gap — `SecurityConfig`'s filter chain already requires real
authentication (session, PAT, or basic) to reach the MCP endpoint at all — it's **privilege
escalation**: any authenticated caller gets full admin read access through this one tool,
regardless of their own actual grants. Investigating *why this was even possible* led to a broader
finding: `NtrlocPrincipal` can be fabricated by any code anywhere (see Part A) — the real fix is
narrower and more structural than "patch this one service."

## Decided direction (confirmed with the user, ready to build)

1. Core services (`EntityManager` foremost) must **demand** a principal, not merely accept one.
   "Demand" means Spring-enforced, fail-fast — not a scattered `if (principal == null)` check
   copy-pasted into business logic.
2. Lock-down-first philosophy: close the gap now, accept that things break (e.g. unattended
   Flowable processes that currently run with no principal), fix what breaks after. Not a careful
   phased rollout.
3. A null-check alone is insufficient — it would not have caught the actual `ProjectionService` bug,
   since `MCP_PRINCIPAL` was never null, just fabricated. The deeper fix is restricting who/what can
   ever construct an `NtrlocPrincipal` in the first place (Part A).
4. Three surfaces reach `EntityManager` today and each needs its own answer: REST (already covered
   by the filter chain), MCP tools (needs work), Flowable process scripts (needs work — this is
   where Part B comes from).

## Part A — Lock down `NtrlocPrincipal` construction (DONE)

Implemented and verified: `mvn -q compile test-compile` clean, full `mvn -q test` green (exit 0, no
`FAIL`/`BUILD FAILURE`, same confirmation method as Part B).

**One design change from the original plan, discovered mid-implementation:** Java's JLS forbids a
record's canonical constructor from having *less* access than the record class itself — a
`public record` cannot have a package-private canonical constructor (compile error: "attempting to
assign stronger access privileges"). So instead of "public type, package-private constructor,"
`ResolvedPrincipal` is now a **package-private record** (no `public` modifier at all) with an
ordinary (now-matching-access) compact constructor. This is actually simpler than the original
plan: nothing outside `org.ntrloc.graph.db.partition.security` ever needs to name the concrete
type `ResolvedPrincipal` — every caller holds the public `NtrlocPrincipal` interface instead.
(The plan originally called for a `copyOf` rebuild factory too, "for the rare legitimate need to
rebuild a bare principal from whatever concrete type was handed in" — added speculatively, then
removed again in the same session once a grep confirmed zero call sites anywhere. No code needs
this today; add it back if and when something actually does.)

What changed:
- `NtrlocPrincipal` sealed: `public sealed interface NtrlocPrincipal extends Principal permits ResolvedPrincipal, NtrlocUserDetails`.
- `ResolvedPrincipal` is now package-private (`record ResolvedPrincipal(...)`, no `public`); its canonical constructor is the implicit one (now legal, since access levels match).
- `NtrlocUserDetails` marked `final` (required: a direct implementer of a sealed interface must be `final`/`sealed`/`non-sealed`) and its constructor made package-private (only `LocalUserDetailsService`, same package, used it).
- `ProjectionService.MCP_PRINCIPAL` (the hardcoded-admin bug that started this whole investigation) is **gone**. This broke at compile time exactly as predicted once `ResolvedPrincipal` became package-private — fixing it required pulling a small piece of Part D forward (see note below), since the module had to compile.
- New test helper `src/test/java/org/ntrloc/graph/db/partition/security/TestPrincipals.java` — same
  package as `ResolvedPrincipal`, so it has constructor access; exposes
  `public static NtrlocPrincipal of(UUID, String, String, String, Set<UUID>, boolean)`. Every test
  fixture across 9 files (`MutationRequestProcessorIntegrationTest`, `SelfLinkIntegrationTest`,
  `PropertyAndCapabilityFilteringIntegrationTest`, `EntityCrossTypeProjectionIntegrationTest`,
  `InstanceReadFilteringIntegrationTest`, `SchemaManagerIntegrationTest`,
  `StateMachineExecutionIntegrationTest`, `PermissionServiceInstanceReadIntegrationTest`,
  `ClusterEventVisibilityRegistryTest`) that used to call `new ResolvedPrincipal(...)` directly now
  calls `TestPrincipals.of(...)` instead; declared-type fields (`SOME_PRINCIPAL`, `ADMIN`, etc.)
  changed from `ResolvedPrincipal` to `NtrlocPrincipal`.

**Note on `ProjectionService` (Part D pulled forward, partially):** rather than just making it
compile, `ProjectionService.projectItems` now resolves the real calling principal the same way
`MutationService.resolvePrincipal` does (Authorization header off `McpTransportContext`, resolved
via `PersonalAccessTokenService.authenticate`), and throws 401 when unresolvable — stricter than
`MutationService`'s "null is a displayable ledger state" stance, which is correct here since this
is a *read* tool and the old behavior handed any caller full admin read access. This is **not**
the full Part D (that still needs `SchemaService`/`ProcessService` wired up, and a decision on
whether this resolution logic becomes shared rather than copy-pasted across three services) —
flagging that Part D's remaining scope is now "finish the other three tools, dedupe the resolution
helper," smaller than originally written.

**Still not done** (genuinely deferred, not a compile necessity): no test exists yet for
`ProjectionService` (or any of the four MCP tool services) at all — none existed before this change
either. Proving the MCP-path enforcement actually works end-to-end is Part C's explicit
requirement ("test-suite proof... for the MCP dispatch path specifically"), not Part A's — doing it
here would be scope creep ahead of Part C's method-security mechanism being decided.

## Part B — Remove `NtrlocPrincipalVariableType`, store a UUID instead (DONE)

Implemented and verified: `mvn -q compile test-compile` clean, full `mvn -q test` green (including a
new `SecurityRepositoryIntegrationTest.findUserById_*` pair). Confirmed before starting that no
shipped BPMN process or Java delegate reads the `"principal"` variable today — it was write-only, so
this was lower-risk than expected. What changed:
- `SecurityRepository.findUserById(UUID)` added, mirroring `findUserByExternalId`.
- `PrincipalResolver.resolveById(UUID)` added; `PRINCIPAL_VARIABLE` constant relocated here from the
  now-deleted class.
- `ProcessAdminController.startProcessInstance` and `ProcessRunAsUserListener` now store
  `principal.id().toString()`, not the principal object.
- `NtrlocPrincipalVariableType.java` deleted; its registration removed from `ProcessEngineConfig`
  (`setCustomPreVariableTypes` call + import).
- Stale comments referencing the deleted class fixed in `NtrlocPrincipal.java` and
  `PrincipalResolver.java` (the latter's class comment also now correctly says nothing reads this
  variable yet, rather than claiming a script "recovers it via a plain
  execution.getVariable(\"principal\")" the way the old, wrong comment did).

**Not done, deliberately deferred**: no dedicated integration test exercises the full
`ProcessRunAsUserListener` → Flowable-persisted-variable → `resolveById` round trip end to end (the
"helloWorld" fixture process completes synchronously, so its runtime variables aren't easily
queryable after the fact without pulling in Flowable's history service — judged lower-value than
moving through the rest of this plan, since nothing consumed this variable before either). Revisit if
a real script ever starts reading `PRINCIPAL_VARIABLE`.

## Part C — demand a real principal, everywhere (DONE)

**Spike result (2026-10-18):** before writing any of the plan below, a quick spike wired
`@PreAuthorize("#principal != null")` onto `PermissionService.canReadItem` to settle the
mechanism question empirically. `@EnableReactiveMethodSecurity` hard-failed with
`IllegalStateException: ... must return an instance of Publisher` — every method in scope here
returns a blocking type, so that annotation is a dead end outright, not a style preference.
`@EnableMethodSecurity` (the servlet one) worked, including proven enforcement (a new test got a
real `AuthorizationDeniedException` before the method body ran, not a `NullPointerException`) —
but only because `#principal != null` reads just the method argument. A follow-up question (would
converting the blocking methods to return `Mono<T>` fix this properly?) was also spiked: yes,
mechanically, but it doesn't make anything actually non-blocking (same blocking body, just wrapped),
and the blast radius was immediately visible — one method's signature change broke one production
call site and ten assertions in one test file. Scaled across every method in scope here (dozens of
call sites across every controller and all four MCP tools), that's a large, pointless refactor for
zero functional gain. This, plus the user's stated worry about *future* checks (not just today's
null-check) that might reference `authentication`/roles — which would silently see nothing under
WebFlux's thread-local-`SecurityContextHolder` gap — led to the decision to fix the actual
underlying mismatch instead of designing around it: migrate off WebFlux first. See that doc for
the full rationale and survey; **this section is frozen until that migration is done.** The spike's
changes were fully reverted; nothing from it remains in the codebase.

Two mechanisms were on the table for `EntityManager` specifically, decided below:

- **(A) Guarantee non-null.** Keep the explicit `NtrlocPrincipal principal` parameter, add a
  Spring-enforced check that rejects `null` (e.g. `@PreAuthorize("#principal != null")` — note
  this checks the *method argument*, not Spring's ambient `SecurityContext`, so it sidesteps the
  unverified "does Reactor Context survive MCP dispatch" question entirely for this specific
  guarantee). Smaller change.
- **(B) Guarantee it's real.** The component stops accepting an externally-supplied principal
  parameter at all; it resolves the current principal itself from Spring's authenticated context on
  every call. Bigger change — touches every caller.

**Decided: (A), given Part A is already in** — a non-null `NtrlocPrincipal` can now only have come
from a legitimate resolution path (`PrincipalResolver`, `LocalUserDetailsService`,
`PersonalAccessTokenService`), since nothing else can construct one. (A) + Part A together close
the gap (B) existed to close, for materially less change.

**Scope question raised and resolved (2026-10-16):** should the null-reject guard apply only to
`EntityManager`, or to every top-level component that accepts an `NtrlocPrincipal` parameter, so
that bypassing `EntityManager` can't also bypass the check? **Decided: every one of them.**
Surveying the codebase found these top-level, principal-accepting methods:

- `EntityManager`: `project(ItemProjectionSpec, ...)`, `project(CollectionProjectionSpec, ...)`,
  `mutate(...)`, `startStateMachine(...)`, `executeTransition(...)`
- `PermissionService`: `canReadItemType`, `canCreateItemType`, `readableItemTypeIds`, `canReadItem`,
  `buildContext`
- `SchemaManager.getSchema`
- `MutationRequestProcessor.process` (and its private helpers that thread the principal through:
  `processItemCreate`/`Update`/`Delete`, `canDeleteItem`, `canCreateLink`, `processLinkCreate`/
  `Update`/`Delete`, `canDeleteLink`, `checkCanWriteItemProperties`, `checkCanWriteLinkProperties`)

This surfaced a real design conflict, not just a mechanical gap: `EntityManager.mutate`,
`MutationRequestProcessor.process`, and `PermissionService.buildContext` all currently *accept*
(and are actually called with) a **null** principal by design — `EntityManager`'s own doc comment
calls it "attribution for the ledger... not a permission gate. An unresolvable/absent principal is
a real, displayable state, not a reason to refuse the mutation." A mutation against an *unprotected*
resource with no identified actor currently succeeds anonymously today.

**Decided (2026-10-16): there is no legitimate anonymous mutation. SEV-0 lockdown means nothing
happens anonymously, full stop.** `EntityManager`'s doc comment claiming principal is "just
attribution, not a permission gate" is itself wrong and gets corrected, not preserved —
`MutationRequestProcessor` isn't selectively permissive for some resources; *all* resources are
protected, so there is no case where a null principal is a legitimate value. This means, beyond
adding the guard: every `@Nullable NtrlocPrincipal` annotation on `EntityManager.mutate`,
`MutationRequestProcessor.process` and its private helpers listed above, and
`PermissionService.buildContext`, is wrong and must come off — principal becomes mandatory
everywhere, not just where it already was.

**Superseded (2026-10-18):** an earlier pass through this doc went back and forth on
`@EnableMethodSecurity` vs `@EnableReactiveMethodSecurity` by reasoning about it (first from each
method's own return type, then from "which web stack is this" per the user's note that WebFlux
apps should use the reactive equivalents). The spike at the top of this section replaced that
reasoning with a measured result: `@EnableReactiveMethodSecurity` isn't a matter of which is more
"correct" for a WebFlux app — it flatly cannot run against a blocking-return-type method at all.
The user's note about matching the annotation to the web stack is still right in general; the
resolution here is to fix the mismatch at its root (migrate the web stack, see
`docs/webflux-to-mvc-migration-plan.md`) rather than pick the annotation that merely doesn't crash.

**Implemented and verified (2026-10-19):**

- `MethodSecurityConfig` (new, `org.ntrloc.graph.security`): bare `@Configuration` class carrying
  `@EnableMethodSecurity` — deliberately not on `SecurityConfig` (which is
  `@ConditionalOnProperty(graph.security.enabled)`, `false` in tests), since the principal guard
  must hold regardless of whether the web filter chain is active.
- `@PreAuthorize("#principal != null")` added to: `EntityManager`'s 5 methods (on the interface,
  not the impl — `EntityManagerImpl` just implements it), `PermissionService`'s 5
  (`canReadItemType`, `canCreateItemType`, `readableItemTypeIds`, `canReadItem`, `buildContext`),
  `SchemaManager.getSchema`, `MutationRequestProcessor.process`.
- **Not annotated, deliberately: `MutationRequestProcessor`'s private helpers**
  (`processItemCreate`/`Update`/`Delete`, `canDeleteItem`, `canCreateLink`,
  `processLinkCreate`/`Update`/`Delete`, `canDeleteLink`, `checkCanWriteItemProperties`,
  `checkCanWriteLinkProperties`) — the original plan listed these, but Spring AOP method security
  cannot intercept a private method called via self-invocation within the same class (the proxy
  only sees calls arriving from *outside* the bean); annotating them would silently do nothing,
  a worse outcome than not annotating them at all. Confirmed by survey these are only ever
  reachable through `process()` itself (no other caller exists anywhere in the codebase), so
  `process()`'s own guard already makes a null principal unreachable here by construction — these
  methods take a plain `NtrlocPrincipal`, not `@Nullable`, with no null-check of their own, and all
  their former `if (principal == null) ...`-shaped dead code removed.
- Every `@Nullable` removed: `EntityManager.mutate`, `MutationRequestProcessor.process` and the
  private helpers above, with every corresponding null-check simplified away (including
  `EntityManagerImpl.commitStateEntry`'s `actor = principal == null ? null : ...`).
- Stale doc comments fixed: `EntityManager`'s interface comment, `EntityManagerImpl.mutate()`'s
  comment, `MutationController`'s comment, `MutationRequestProcessor`'s class comment and
  `process()`'s own `actorExternalId` comment, `ProjectionService`'s comment referencing the old
  (now-corrected) `MutationService` behavior — all previously claimed principal was
  "attribution-only, not a permission gate" or described a "not-yet-built" permission check that
  already existed and failed closed.
- **One real production gap found and fixed, not just a doc/annotation pass:** `MutationService`
  (an MCP tool) resolved a principal that could be null and passed it straight into
  `entityManager.mutate(...)` with no check of its own — unlike `ProjectionService`, which already
  refused an unresolvable caller. Added the same explicit `ResponseStatusException(UNAUTHORIZED)`
  guard `ProjectionService` already had, rather than letting this surface as an
  `AuthorizationDeniedException` from deep inside `EntityManager` instead of a clean 401 at the
  tool boundary.
- **A second, separate finding surfaced but deliberately not acted on this pass:**
  `MarkerAssignmentService` also writes to the ledger (`coordinator.prepare(...)`), but takes a
  raw `String actorExternalId`, not an `NtrlocPrincipal` — the original survey's grep for
  `NtrlocPrincipal`-typed parameters never caught it. It currently has **zero production
  callers** (confirmed via grep — only test fixtures reference it), so there is no live gap today,
  but it would become one the moment some future controller wires it up without its own guard.
  Flagged to the user; not changed, since it's outside today's literal scope and the right fix
  (take an `NtrlocPrincipal` instead of a bare string, guarded the same way) is a design decision
  for whoever builds that caller, not a drive-by edit now.
- Test fallout: the one known test
  (`MutationRequestProcessorIntegrationTest.nullPrincipal_isAcceptedAndAttributesNothing_attributionIsBestEffortNotRequired`)
  rewritten to assert `AuthorizationDeniedException`, renamed
  `nullPrincipal_isRejectedBeforeTheMethodBodyRuns`. Six *more* tests surfaced only once the suite
  actually ran — a multi-line `assertThatThrownBy(() -> processor.process(..., null))` pattern a
  single-line grep sweep missed entirely (a real lesson: grep sweeps for multi-argument call sites
  need to account for line wrapping, not just assumed caught by a single regex pass). All six
  (`itemCreateMutation_withANullPrincipal_reportsAValidationError` and five siblings for
  update/delete/link-create/update/delete) asserted the *old* behavior (a `MutationValidationException`
  naming the specific denied permission) — now fully redundant with the one rejection test above,
  since `@PreAuthorize` rejects before any mutation-type-specific content is ever inspected, so
  keeping 6 near-duplicate assertions of the identical outcome would be low-value
  coverage-chasing. Deleted, not rewritten.
- **New: MCP dispatch path proof**, per the user's standing requirement ("prove, via the test
  suite, that the MCP tool methods are subject to security restrictions and still work"). Zero
  tests existed for any of the four MCP tool services before this. Added
  `ProjectionServiceIntegrationTest`/`MutationServiceIntegrationTest` — both call the real
  autowired Spring bean (so every proxy in the chain, including `EntityManager`'s new guard, is
  genuinely in play), with a Mockito-mocked `McpSyncRequestContext` standing in only for the one
  piece that would otherwise need real MCP wire-protocol machinery (`transportContext()`); each
  proves both the null-rejection path and, more importantly, that a real PAT-authenticated call
  succeeds end to end through the new guards without being incorrectly rejected.
- The original worry about Reactor Context propagation across MCP's off-event-loop dispatch turned
  out not to matter empirically: `@PreAuthorize("#principal != null")` is argument-only AOP
  interception, synchronous at the method-call level regardless of which thread performs the
  call — it was never actually exposed to the Reactor-Context question the way an
  `authentication`/`hasRole(...)`-referencing expression would be. Confirmed, not just reasoned
  through: the new MCP dispatch tests pass.
- `mvn clean test`: 993/993, 0 failures, exit 0 (995 after Tier 3 → 989 after removing the 6
  redundant tests → 993 after adding the 4 new MCP dispatch tests).
- Live-verified against the rebuilt/restarted `domain-1`: logged in, hit `/api/schema`
  (`SchemaManager.getSchema`, 200), `/api/projection/collection` (`EntityManager.project`, real
  data returned), and `/api/mutation` (`EntityManager.mutate` → `MutationRequestProcessor.process`,
  a real item created then deleted, both 200) — confirming the new guards don't break the real,
  authenticated happy path, not just the automated suite.

## Part D — MCP tools: remove synthetic principal, unify resolution (DONE)

Blocked behind Part A (fabricating `MCP_PRINCIPAL` becomes impossible once sealed) and informed by
Part C (how a real principal gets obtained/demanded) — both done before this was started.

- [x] `ProjectionService.MCP_PRINCIPAL` removed (Part A); resolves the real calling principal and
      rejects (401) when unresolvable.
- [x] `MutationService` likewise now rejects (401) when unresolvable, instead of silently passing
      a null principal into `entityManager.mutate(...)` (Part C — see that section's "one real
      production gap found" note).
- [x] **Dedupe.** The identical `resolvePrincipal`/null-check/throw logic that used to be
      copy-pasted in both `ProjectionService` and `MutationService` is now one shared
      `@Component`, `McpPrincipalResolver`, with a single `requirePrincipal(McpSyncRequestContext)`
      method that resolves the caller (via the same `McpTransportContextConfig`/
      `PersonalAccessTokenService.authenticate` pattern as before) and throws
      `ResponseStatusException(UNAUTHORIZED)` directly when unresolvable — callers no longer do
      their own null check. Confirmed this doesn't replace the need for `EntityManager`'s own
      `@PreAuthorize` (Part C): method security *guards* a resolved principal, it doesn't produce
      one — there is still no Spring mechanism that hands an MCP tool method its caller's identity
      automatically the way a servlet filter chain does for a controller, so this manual
      resolution step stays necessary regardless.
- [x] `SchemaService.getSchema()` now takes an `McpSyncRequestContext` parameter and calls
      `principalResolver.requirePrincipal(...)` before doing anything else, same floor as the
      other three tools. Deliberately **still** calls `schemaManager.getAdminSchema()`, not
      `schemaManager.getSchema(principal)` — the response stays unfiltered by identity, exactly as
      scoped ("nothing in its response is filtered by identity today," confirmed by the user: "all
      of those tools" means requiring a real caller, not adding content filtering nothing asked
      for). The new class comment states this explicitly so it doesn't read as an oversight later.
- [x] `ProcessService`'s five `@McpTool` methods (`listProcesses`, `runProcess`, `listTasks`,
      `claimTask`, `completeTask`) all gained the same `McpSyncRequestContext` parameter and
      `requirePrincipal` call, called before each method's existing `try` block (so the now-dead
      `catch (ResponseStatusException e) { throw e; }` arms that existed in `SchemaService`'s
      siblings weren't needed here and were correctly left out). The resolved principal still
      isn't used for anything beyond existing — `listTasks` still returns every task unfiltered,
      `claimTask`/`completeTask` still take an explicit assignee/userId parameter rather than
      inferring one from the principal. That bypass of ntrloc's own marker/permission system is a
      separate, still-open gap (open question 2 below), explicitly not folded into this pass.
- [x] **Tests.** `ProjectionServiceIntegrationTest`/`MutationServiceIntegrationTest` already
      existed from Part C. Added `SchemaServiceIntegrationTest`/`ProcessServiceIntegrationTest`,
      same pattern (mocked `McpSyncRequestContext`, real autowired beans, a real
      `PersonalAccessTokenService`-issued token) — one test per new tool proving an unresolvable
      caller is rejected, one proving a real authenticated call still succeeds. `mvn clean test`:
      997/997 (993 + 4 new). Live-verified: rebuilt (`mvn clean install` from repo root), restarted
      `runtimes/domain-1`, confirmed clean boot ("Tomcat started on port 9090", "Registered tools:
      8") — a `McpPrincipalResolver` wiring mistake across four services would have failed context
      startup immediately, not just a test.

## Part E — Ledger/provenance work (DONE)

This was the *original* ask before the security investigation took over. Design finalized
2026-10-19 (superseding the original per-page-ledger-enrichment sketch below, which is kept
struck through for history, not as live guidance), implemented and verified the same day.

~~`ProjectedItem` gains `createdBy`/`createdDate`/`modifiedBy`/`modifiedDate`, resolved from the
ledger (an item's one `ITEM_CREATE` row = created; most recent row of any type = modified).
Enrichment happens in `EntityManagerImpl` via new `LedgerRegisterCoordinator` read methods, batched
per page.~~ — superseded: computing these at read time, per page, can't be sorted or filtered at
the SQL level the way `createdAt`/`updatedAt` already are today (`RegisterPartitionManager.
SYSTEM_SORT_COLUMNS`) — and the user explicitly wants these new fields sortable/filterable too.

**Naming (resolves open question 1):** consistent `created_date`/`created_by` on both sides, not
`created_at`. The ledger's own `created_at` column is renamed to `created_date`; `actor_external_id`
(TEXT) is replaced outright by `created_by` (UUID → `security_user.id`) — a ledger entry is
append-only, so it only ever has a "created," never an "updated," pair. These describe *who
created the ledger entry, and when* — nothing else.

The register (`register_item` **and** `register_link`, symmetrically — confirmed in scope) gains
real `created_date`/`created_by`/`updated_date`/`updated_by` columns. Critically, on the register
side these describe **the item/link the row represents**, not the row itself — a semantically
different thing from the ledger's own `created_date`/`created_by`, despite the shared names.
`created_date`/`created_by` are set once, at the item/link's first commit, and must never change
after that, no matter how many times the item/link is later updated. `updated_date`/`updated_by`
are **null until the item/link is actually updated for the first time** — a brand-new item/link
has no "updated" at all, not an updated equal to its own creation (corrected 2026-10-19 after the
user caught exactly this in a live projection response; the original design below briefly, and
wrongly, had creation also populate updated_date/updated_by with the same values — struck through
in place rather than silently rewritten, see the implementation changelog's own note). Once set,
`updated_date`/`updated_by` take the exact values of whichever ledger entry's own
`created_date`/`created_by` most recently caused a committed change to that item/link — not a
fresh, independently-generated timestamp at register commit time, even though in practice the two
moments are close together.

**Why this isn't free (the real implementation wrinkle found while discussing this with the
user):** `register_item`/`register_link` rows are never updated in place —
`stageItemChange`/`stageLinkUpdate` always insert a brand-new row (new UUID) carrying the merged
properties, and the old committed row is deleted at commit (`LedgerRegisterCoordinatorImpl`'s own
comments already say this plainly). Today, both `created_at` and `updated_at` come from the same
`DEFAULT NOW()` at insert time — meaning `created_at` *silently resets on every update* today,
contrary to what its name implies. (Confirmed no existing test relies on `createdAt` surviving an
update — this isn't a regression, just a previously-unexercised gap.) Implementing the real
semantics above requires `stageItemChange`/`stageLinkUpdate` to explicitly read the superseded
row's `created_date`/`created_by` and carry them forward unchanged into the new row, while a
brand-new row (`stageItemCreate`/`stageLinkCreate`) takes the first ledger entry's values for
`created_date`/`created_by` only, leaving `updated_date`/`updated_by` null (not also populated
from that same entry — see the correction note above). `RegisterPartitionManager` stays fully
ledger-ignorant throughout — it only ever
receives plain `UUID`/timestamp parameters from `LedgerRegisterCoordinatorImpl` (the one place
allowed to know about both ledger and register), the same way it already receives `itemId`/
`properties`/`transactionId` today; it never imports a ledger type or queries a ledger table.
- `register_item`/`register_link`'s own SQL columns are renamed too (`created_at`→`created_date`,
  `updated_at`→`updated_date`), not just the API-facing field names — full terminology consistency
  end to end, per the user's explicit correction (ledger and register use the same words
  throughout, down to the column level), plus new `created_by`/`updated_by` (UUID, NOT NULL,
  references `security_user.id`) columns on both tables.
- `SchemaContentResolver.SYSTEM_SORTABLE_FIELDS` and `RegisterPartitionManager.
  SYSTEM_SORT_COLUMNS`/`SYSTEM_SORT_COLUMNS_ACROSS_TYPES`: rename the `"createdAt"`/`"updatedAt"`
  keys to `"createdDate"`/`"updatedDate"`, and add `"createdBy"`/`"updatedBy"` as new sortable
  system fields alongside them.
- `ProjectedItem` and `ProjectedLink` both gain `createdDate`/`createdBy`/`updatedDate`/
  `updatedBy`, read directly off the register row — no ledger read at projection time at all, since
  the register row now carries the authoritative values itself.
- **Filtering scope (resolved 2026-10-19):** sortable now; filtering by any system field
  (createdDate/createdBy/updatedDate/updatedBy, and the pre-existing itemId/itemType/
  visibilityState) doesn't exist at all today — `RegisterPartitionManager.translatePredicate`
  only ever resolves a predicate's field against a real schema property, no system-field branch
  exists. Building that (a new predicate type, date-range + UUID-equality handling) is explicitly
  **deferred as separate, later work**, not part of this pass — ship these four fields as real,
  sortable, projection-visible columns now.
- **`MarkerAssignmentService` → `ManualMarkerAssignmentService`** (renamed, per the user: if its
  job is manual marker assignment, its name should say so), and its `addItemMarker`/
  `removeItemMarker` now take an `NtrlocPrincipal` instead of a bare `actorExternalId: String` —
  the coordinator itself never reads `SecurityContextHolder` (every principal in this codebase is
  an explicit parameter, never implicit, per Part C), so the caller must already hold a real,
  resolved principal, same pattern as every other top-level principal-accepting method. This is
  unrelated to that service's own still-open "does it have value with zero non-test callers?"
  question from the previous session — that stays separately deferred; this is just the minimum
  needed for its one `coordinator.prepare(...)` call to keep compiling once that call demands a
  UUID actor instead of a string.
- **Ledger schema shape (resolves open question 3):** clean cutover, no backfill. `actor_external_id`
  is replaced outright by `created_by`; no historical-row preservation/resolution step.
- **Test coverage, explicitly required by the user, not optional:** both items and links need
  tests proving (a) `created_date`/`created_by` are set correctly at creation, (b) neither changes
  across a subsequent update, no matter how many updates happen, and (c) `updated_date`/
  `updated_by` correctly reflect the most recent update's actor/time each time — and, separately,
  that an item/link which has *never* been updated has no stale/fabricated `updated_date`/
  `updated_by` distinct from its `created_date`/`created_by`.

**Implementation changelog (2026-10-19):**

- `V1_0_0_1__baseline.sql` edited directly (this project's established convention — see the
  "Removed Flyway scripts, everything is baselined for now" commit; there is no incremental
  migration history to preserve). `ledger_entry.created_at`→`created_date`, `actor_external_id`
  (TEXT, nullable)→`created_by` (UUID, NOT NULL, `REFERENCES security_user(id)`). `register_item`/
  `register_link`: `created_at`/`updated_at`→`created_date`/`updated_date`, plus new
  `created_by`/`updated_by` (UUID, NOT NULL, same FK). No `DEFAULT NOW()` anywhere on these columns
  any more — the app always supplies an explicit value now (see below), so provenance never
  silently depends on the DB's own clock. Clean cutover (open question 3): no backfill, and since
  editing the baseline in place changes its Flyway checksum, the dev Postgres database itself had
  to be dropped and recreated before the next boot — expected and already authorized by the "no
  data preserved" decision, not a surprise side effect.
- `LedgerPartitionManager.append`/`LedgerRegisterCoordinator.prepare`: both signatures changed
  from a nullable `String actorExternalId` to a required `UUID actorUserId/createdBy` plus an
  explicit `OffsetDateTime createdDate` parameter, generated once by the caller (`MutationRequestProcessor`,
  `EntityManagerImpl.commitStateEntry`, `ManualMarkerAssignmentService`) and threaded unchanged
  into both the ledger insert and every register staging call in that same `prepare()` invocation
  — byte-for-byte identical provenance between a ledger entry and the register row(s) it produces,
  not two independently-generated timestamps that merely happen to be close. `LedgerEntryRecord`
  (the per-row metadata `readItemStreamWithMetadata` exposes) changed the same way; `LedgerPropertyHistoryService`
  now resolves display names via `SecurityRepository.findUserById` instead of `findUserByExternalId`.
- ~~`RegisterPartitionManager`: `stageItemCreate`/`stageLinkCreate` take the same `actorUserId`/
  `createdDate` pair for both the created_* and updated_* columns (creator is trivially the first
  updater).~~ — **WRONG, corrected 2026-10-19** (see the correction note earlier in this doc): the
  user caught this live, in a real projection response, right after this was first implemented —
  an item/link that's only ever been created must show no `updated_date`/`updated_by` at all, not
  values equal to its own creation. Fixed same-day: `updated_date`/`updated_by` are now nullable on
  both tables (`ALTER`ed in place in the baseline, same "edit the one migration" convention), and
  `stageItemCreate`/`stageLinkCreate` pass `null` for both instead of the create values.
  `insertItemRow`/`insertLinkRow`'s `INSERT` needed explicit `::timestamptz`/`::uuid` casts on
  that pair specifically — without a cast, the driver can't infer a SQL type for a bound `null` and
  the create path fails outright. `ProjectedItem`/`ProjectedLink`'s own `updatedDate`/
  `updatedByUserId`/`updatedBy` are now `@Nullable`, and `ProjectedUserInfoEnricher` (see below)
  skips a null `*UserId` when collecting ids to resolve, rather than letting it ride into
  `SecurityRepository.findUsersByIds`' own `IN (:ids)` binding.
  `stageItemChange`/`stageLinkUpdate` now also read the superseded row's own
  `created_date`/`created_by` (extended the existing "read current row" query each already had)
  and carry those two forward unchanged into the replacement row, while `updated_date`/`updated_by`
  take the new values — this is the carry-forward logic the design section above flagged as the
  real implementation wrinkle. `commitItem`/`commitLink` no longer stamp `updated_at = NOW()` at
  all: staging already wrote the correct final value from the exact same `prepare()` call, and a
  fresh `NOW()` at commit time would have silently drifted from the ledger entry that actually
  caused the change. `RawItem`/`LinkRow` (internal row-mapping records) and all their SQL (the
  single-type, cross-type, and `projectOne` queries, plus the link-fetching join and the
  cross-type UNION branch builder) gained the four columns; `SYSTEM_SORT_COLUMNS`/
  `SYSTEM_SORT_COLUMNS_ACROSS_TYPES`/`SchemaContentResolver.SYSTEM_SORTABLE_FIELDS` renamed and
  extended as described above.
- `ProjectedItem`/`ProjectedLink`: both gained the four trailing fields, populated directly from
  the register row with no ledger read at projection time. A link's own four fields (describing
  the link) are distinct from the nested linked item's own four (describing that item) — both are
  populated, from `register_link` and `register_item` respectively, in the same query.
- `MarkerAssignmentService`→`ManualMarkerAssignmentService` (see above): `addItemMarker`/
  `removeItemMarker` now take an `NtrlocPrincipal`, `@PreAuthorize("#principal != null")`-guarded
  like every other top-level principal-accepting method from Part C.
- **Tests.** New dedicated file, `RegisterProvenanceIntegrationTest` (2 tests, one for items, one
  for links), going through `LedgerRegisterCoordinator.prepare`/`commit` directly and reading back
  via `RegisterPartitionManager.projectOne` — the real projection path, not raw SQL — proving
  exactly what the user required: `created_date`/`created_by` survive two further updates by two
  different actors completely unchanged, while `updated_date`/`updated_by` correctly move to each
  new actor/time in turn, for both an item and a link. Separately, the ~20 existing test call
  sites across 13 files that fed `coordinator.prepare(...)`/`ManualMarkerAssignmentService` a
  `null` or fake literal string actor (now impossible — the FK is `NOT NULL`) were each fixed to
  use a real, `SecurityRepository.createUser`-backed principal; one additional unbacked-principal
  file (`SelfLinkIntegrationTest`) was found and fixed the same way via a genuine FK-violation
  runtime failure on the first full suite run, not a compile error — a full sweep afterward
  confirmed zero remaining unbacked `TestPrincipals.of(UUID.randomUUID()...)` fixtures anywhere in
  the suite. `mvn clean test`: 999/999 (997 + 2 new). Live-verified: dropped and recreated the dev
  Postgres database (required by the baseline edit, see above), rebuilt (`mvn clean install` from
  repo root), restarted `runtimes/domain-1` — confirmed Flyway applied the edited baseline cleanly
  against the empty schema ("Successfully applied 1 migration") and the app boots with every
  touched bean wired correctly ("Tomcat started on port 9090", "Registered tools: 8", no startup
  exceptions).

## Open questions (unresolved, need the user's call — carried over from the Artifact)

1. ~~**Naming scope**: does "`createdAt`/`updatedAt` → `createdDate`/`updatedDate`" cover just the
   provenance concept, or also the API-facing sort-field names (`SchemaContentResolver`'s
   `SYSTEM_SORTABLE_FIELDS`, `RegisterPartitionManager`'s `SYSTEM_SORT_COLUMNS` /
   `SYSTEM_SORT_COLUMNS_ACROSS_TYPES`)?~~ — RESOLVED 2026-10-19: both, and the ledger's own
   `created_at` is renamed to `created_date` too, for terminology consistency across ledger and
   register — see Part E for the full naming/semantics decision (register's fields describe the
   item/link, not the row; ledger's describe the ledger entry itself, and only ever has a
   "created" pair, never "updated"). `register_link` gets the identical treatment as
   `register_item`, confirmed in scope.
2. **`ProcessService` scope**: its task operations don't go through ntrloc's permission system at
   all, separately from the principal-resolution question (Part D). **Explicitly deferred**
   2026-10-19 — not in scope for Part E; revisit as its own separate piece of work later.
3. ~~**Ledger schema shape**: replace `ledger_entry.actor_external_id` outright with a UUID column, or
   add alongside? Does preserving/backfilling historical rows matter in this environment?~~ —
   RESOLVED 2026-10-19: replace outright, clean cutover, no backfill of historical rows.
4. ~~**Sequencing**: one combined change, or separate reviewable steps?~~ — RESOLVED 2026-10-19:
   one single combined pass for everything in Part E (naming/schema, register carry-forward
   logic, API rename, tests). `ProcessService` (question 2) stays separately deferred regardless.
5. ~~**New, from Part C**: does Part A (construction lockdown) make option (A) sufficient for
   `EntityManager`, or is (B) (no externally-supplied parameter at all) still wanted regardless?~~
   — RESOLVED 2026-10-16: yes, (A) is sufficient, given Part A. Scope also widened to every
   top-level principal-accepting method, not just `EntityManager`, and the null-is-valid-for-
   mutation-attribution carve-out was explicitly killed — see Part C for the full decision.
6. **New, from Part B**: after removing `NtrlocPrincipalVariableType`, is storing a bare UUID and
   re-resolving on every read acceptable performance-wise for whatever script-task call volume this
   system actually sees, or does *something* warrant a request-scoped (not process-instance-scoped)
   cache? Lean "just re-resolve, it's a single indexed lookup" unless profiling says otherwise — not
   raised by the user, flagging defensively since it's the kind of thing a future reviewer might ask.

## Suggested resume order

1. ~~Finish the not-yet-checked item in Part B~~ — DONE.
2. ~~Part A~~ — DONE.
3. ~~Part B~~ — DONE.
4. ~~Resolve open question 5~~ — DONE (resolved yes, see Part C).
5. ~~`docs/webflux-to-mvc-migration-plan.md`, Tiers 0–4 and 6~~ — DONE (Tier 5, MCP transport, is
   tracked separately there, and is itself now also DONE).
6. ~~Part C~~ — DONE.
7. ~~Part D~~ — DONE. All 4 MCP tools now require a real, resolvable principal; see that section.
8. ~~Resolve remaining open questions~~ — DONE 2026-10-19: 1, 3, 4 resolved (see Part E and the
   open-questions list for the full decisions); 2 (`ProcessService`'s marker/permission bypass)
   explicitly deferred to its own later piece of work, not part of Part E.
9. ~~Part E~~ — DONE. One combined pass, per resolved question 4; see Part E's own section for
   the full implementation changelog (schema, carry-forward logic, API rename, projection wiring,
   tests, live verification).
10. **Nothing left in this doc.** Open question 2 (`ProcessService`'s marker/permission bypass)
    is the only unresolved item, and it's explicitly its own separate, later piece of work.
