# ntrloc Design Summary: UI Hosting as a Platform Capability

## Topics: Admin UI Technology Direction, Multi-Mount Hosting, Server-Rendered Modules, Dynamic (JSR 223) Controllers, Uptime

This is a checkpoint of an in-progress design conversation, not a finished spec. Revise/refine in
place as the thinking develops further.

---

## 1. The Reframe: Hosting UIs Is a Platform Capability, Not an App Choice

The starting question was "should the admin UI use Angular, or something else?" That framing was
replaced by a bigger one: **ntrloc should offer first-class, production-grade support for
building/hosting user interfaces, from day one** — the same way it already offers schema,
mutations, security, and binary storage as platform capabilities, not app-specific concerns.

Motivation, stated directly: it's such a common thing for teams building on a platform to need
their own UI that users would wonder why ntrloc didn't support it. There's already a concrete,
real second consumer of this: a separate ntrloc-based application (in development, outside this
repo) that needs to offer its own UI in addition to the default admin UI.

**Consequence for the Angular-vs-other-tech question**: it's lower-stakes than it first appeared.
If UI hosting is a general platform capability, the platform's job is to serve assets/host logic
and wire it into the existing auth/data APIs — not to mandate a frontend stack. The admin UI
becomes the *first tenant* of this capability, not the mandated house style. A team can bring
React, Angular, HTMX, or plain HTML; ntrloc doesn't care, as long as it talks to the general-purpose
APIs (`/api/entity/projection`, `/api/mutation`, `/api/schema`, etc.) like any other client — consistent with
the "API Reuse" principle already stated in `ui-development-goals.md`.

---

## 2. Current State (Checked Before Designing)

- **`domain-graph-experimental-ui`** — the existing Angular 21 + Material admin app. Fully
  functional schema editor (item list/detail, property grid, links table, controlled-list dialog,
  save-confirm dialog). Search/projections and user management are thin stubs. No Graph Mutations
  UI exists at all (the backend for it — `/api/mutation`, validation, cascades — was only just built).
- **It has no real hosting story in production today.** The module builds the Angular app and
  copies `dist/` into its own `target/classes/static`, but nothing depends on that jar — neither
  `domain-1` nor `domain-graph-experimental` consumes it. In practice it only runs via `ng serve`
  on its own dev port, proxying `/api/*` to the backend (port 9090), including a documented
  personal-access-token flow (`proxy.conf.js`) so the dev server can talk to a secured backend.
  This is a genuine gap to fill, not a working mechanism to replace.
- **`domain-graph-starter`** (the older, JanusGraph-based engine's shared starter module) already
  has a real, working Thymeleaf + HTMX admin UI prototype: `AdminConfiguration` (externalizes the
  template resource location via a Spring property, defaulting to classpath but overridable to an
  external file path — and disables Thymeleaf's template cache when the location is external, so
  template edits take effect without a restart), `layout.html` + a layout-dialect content slot,
  and a `fragments/` directory (`newItem.html`, `itemDefinitionFields.html`) built for HTMX partial
  swaps. **This can't be reused directly** — its controllers (`SchemaController`, `ItemController`,
  `SearchController`) call into `domain-graph-starter`'s own JanusGraph-based APIs, not
  `domain-graph-experimental`'s. The *pattern* (externalized, non-cached resource location;
  layout-dialect composition; htmx fragments) is worth keeping; the code is not directly reusable
  since `domain-1` (the actively running new-engine domain) depends only on
  `domain-graph-experimental`, never on `domain-graph-starter`.
- `domain-graph-experimental` is **Spring WebFlux** (reactive) — confirmed via `EntityController`
  taking `ServerHttpRequest`/`Authentication`, not classic servlet types. Any hosting mechanism
  must be built on WebFlux's reactive resource-serving APIs, not classic Spring MVC.
- Each **domain** (`domain-1`, `domain-2`, and any future ntrloc-based app) is its own separately
  deployed Spring Boot process with its own database — not multiple tenants sharing one JVM. This
  turns out to matter a lot for the trust model below.

---

## 3. Two (Later Three) Distinct Hosting Mechanisms

"Hosting a UI" is not one mechanism — it splits by what's actually being hosted:

1. **Precompiled SPA bundles** (Angular, React, or plain static HTML/JS with no server templating)
   need a genuinely new capability: a **configurable multi-mount static-file server** (path prefix
   → resource location), since Spring doesn't offer that generically today — its default
   static-resource handling is one fixed classpath convention, not a registry of independently
   configured mounts.
2. **Server-rendered apps** (Thymeleaf + HTMX, e.g. a rebuilt admin UI) need **no new mechanism at
   all** — Spring already supports this naturally: any module added as a dependency contributes
   its own `@Controller` beans and templates under whatever path prefix it chooses, the same way
   `domain-1` already picks up `domain-graph-experimental`'s controllers. The "mount" here is just
   a Maven dependency plus a path-prefix convention (not yet designed in detail — deferred, see
   Section 7).
3. **Dynamic, script-defined controllers** (Section 5) — a third tier, for logic that needs to be
   added/changed without a compile-and-redeploy cycle at all.

**Decision (this session)**: build mechanism 1 first. The admin UI is registered as its first (and
for now, only) mount. It initially went up wired to the *existing, already-built* Angular app, to
prove the mechanism against something real and already-tested before touching UI content — but the
"rebuild admin UI content in a lighter stack" question was resolved shortly after (not left open as
originally expected here) and a fresh admin UI has since replaced it as the mount's content. See
Section 9 for where that stands.

**Where mechanism 1 lives (revised, July 2026)**: originally built as its own small module
(`ui-hosting`), kept out of `domain-graph-experimental` on the assumption that the other
ntrloc-based application in development might sit on a different graph engine entirely and
shouldn't have to pull one in just for UI hosting. That assumption turned out to be wrong: every
ntrloc-based application extends `domain-graph-experimental` itself, so there was never a second,
engine-independent consumer to justify the split. `ui-hosting` has since been absorbed directly
into `domain-graph-experimental` (`org.ntrloc.ui.hosting` package, `admin-ui/` at the module root)
— any domain runtime that depends on `domain-graph-experimental` now gets UI hosting and the admin
UI for free, with one dependency instead of two.

---

## 4. Trust Model and the Practical Risk (WebFlux, Not Malice)

**Decided**: UI/dynamic-controller creation is a **domain admin function only** — not exposed to
arbitrary end users, not multi-tenant within a process. Combined with the one-domain-per-JVM fact
from Section 2, this reframes the risk model:

- There is no "other tenant's data" to protect against within one process — a domain admin's
  script or hosted app can only affect their own domain's deployment.
- A domain admin already holds comparably broad power (schema definition, security marker
  assignment), so the *malicious actor* threat model mostly falls away for this audience.
- **The practical risk that remains, specific to this stack**: `domain-graph-experimental` runs on
  a small, fixed WebFlux event-loop thread pool. A blocking or runaway script/render (a
  non-offloaded synchronous call, a busy-loop, an accidental `Thread.sleep`) doesn't just misbehave
  in its own corner — it competes for the same threads every other request in that domain depends
  on, stalling the whole domain's request handling.

**Decision**: all dynamic execution (scripts, and dynamic template rendering more broadly) runs on
a **dedicated, bounded executor/scheduler**, isolated from the WebFlux event-loop threads, with a
wall-clock execution timeout to kill runaway executions. One shared bounded pool per domain is the
starting design; per-app/per-mount isolation within a domain is a possible later refinement, not
needed for the admin-only v1.

---

## 5. Dynamic (JSR 223) Controllers — Design, Not Yet Built

A further idea, explored but **not yet built**: let domain admins define custom server-side
endpoint logic ("controllers," loosely) as scripts, via the standard Java Scripting API
(`javax.script`) — no compile/deploy cycle to add or change one.

- **Engine-agnostic by construction**: `ScriptEngineManager` discovers engines by name via
  `ServiceLoader`. The hosting mechanism just looks up an engine by a language tag stored on each
  registered script (`"groovy"`, `"js"`, ...) — it does not hardcode to one engine. This gives
  "support Groovy and JavaScript out of the box, and let a team add another JSR223-compatible
  engine as their own dependency" for free, as a natural consequence of using the standard API
  rather than a bespoke one.
- **Bundled defaults**: Groovy (`groovy-jsr223`) and GraalVM's JS engine (via its JSR223 adapter).
  Not standalone Nashorn — it was removed from the JDK in Java 15 and is effectively legacy;
  GraalVM is where the JVM's JS investment has gone since.
- **Why Groovy specifically, for this audience**: domain admins are a Java-adjacent audience.
  Groovy's syntax is close enough to Java for low ramp-up, and its Java interop is *native*, not a
  cross-language marshalling layer — exposing the same host API surface (below) to a Groovy script
  is close to friction-free, unlike GraalVM JS/Python which need real type translation for anything
  beyond primitives.
- **Host API surface**: a script must go through the same validated, permission-checked pathway any
  other client uses (`EntityManager`, the mutation pipeline) — never a backdoor into raw
  JDBC/internal classes. This is the same principle as "API Reuse" in Section 1, applied to scripts.
- **Uptime**: scripts need the same no-restart-to-see-changes property templates already have via
  `AdminConfiguration`'s cache-disabling. Concretely: store scripts with a way to detect changes
  (version/timestamp), and have the invocation path check "is my compiled script still current"
  before running, recompiling from source on change rather than once at startup.
- **Operational caveat worth designing for, not discovering in production**: repeatedly compiling
  new script versions in a long-lived JVM needs care around class-loader/Metaspace accumulation
  (Groovy's dynamic script compilation can leak classes if old compiled versions are never
  released).

---

## 6. Uptime as a Cross-Cutting Property

A theme, not just a JSR223 concern: keeping UI/controller behavior *user-defined and dynamic*
means a fix or enhancement can ship without a server restart — real operational value. This
already holds, to different degrees, across all three mechanisms:

- **Static SPA mounts** — hot by nature; serving whatever's currently at the resource location, no
  compilation step involved at request time.
- **Server-rendered templates** — hot via non-cached template resolution, already precedented in
  `AdminConfiguration` (cache disabled whenever the resource location isn't `classpath:`).
- **Script-based endpoints** — needs an explicit change-detection + recompile-on-demand step
  (Section 5); the one mechanism that doesn't get this property automatically.

---

## 7. Resolved So Far

- UI hosting is a first-class ntrloc platform capability, not an app-specific concern — the admin
  UI is its first tenant, not its mandated house style.
- Three distinct hosting mechanisms exist: static multi-mount (new), server-rendered
  Maven-dependency modules (uses Spring's existing composition, no new mechanism), and dynamic
  JSR223-scripted controllers (new, deferred).
- **Decision: build static multi-mount hosting first**. Initially wired to the existing Angular
  admin UI to prove the mechanism against something real and already-tested; a fresh admin UI has
  since replaced it as the mount's actual content (Section 9). Originally built as its own
  `ui-hosting` module, later consolidated directly into `domain-graph-experimental` once it became
  clear every ntrloc-based application extends that module anyway (Section 3).
- Trust model: domain-admin-only authorship; each domain is its own JVM/deployment, so the
  practical risk is a blocking script/render stalling the WebFlux event loop, not a malicious actor
  — addressed via a dedicated bounded executor with a wall-clock timeout, not heavy sandboxing.
- JSR223 scripting: engine-agnostic via `ScriptEngineManager`; Groovy + GraalVM JS bundled by
  default; a script's host API surface is the same validated pipeline (`EntityManager`, mutations)
  any other client uses.
- Uptime (no-restart-to-deploy-a-change) is a deliberate, cross-cutting property, already partially
  proven in this codebase via `AdminConfiguration`'s cache-disabling for external template
  locations.

## 8. Explicitly Open / Deferred

- The server-rendered module convention's concrete shape (path-prefix conventions to avoid
  collisions between multiple hosted apps' templates/controllers within one domain) — named, not
  designed in detail.
- JSR223 dynamic controllers — the whole mechanism (script storage/registration model, the exact
  host API surface exposed as script bindings, the change-detection/recompile implementation,
  Groovy class-loader lifecycle management) is designed at the conceptual level only, not built.
- Whether per-app/per-mount execution isolation (vs. one shared bounded executor per domain) is
  ever needed — deferred until the admin-only v1 proves insufficient.
- ~~Whether/how to eventually rebuild the admin UI's own content in a lighter stack~~ — **resolved,
  see Section 9**: Alpine.js + native Web Components + vendored Google Material Web, no build step.
- The exact configuration shape for the multi-mount registry (property structure, validation) —
  being designed now as part of building it.

---

## 9. The New Admin UI — Current State (Updated July 2026)

Mounted at `/admin` (`domain-graph-experimental/admin-ui/`) via the static multi-mount mechanism
from Section 3, alongside the still-running Angular app (its own separate `ng serve` dev server,
not this mount registry) — kept side-by-side deliberately, as a live visual/behavioral reference
rather than a code source. Nothing from the Angular app is reused directly. Originally mounted at
`/admin-next` to avoid colliding with `SchemaAdminController`'s `/admin/schema` REST path; freed up
once that controller (and the rest of the backend's REST API) moved under `/api/**` (July 2026).

### Stack and the constraint that drove it

**No compilation/build step of any kind, ever.** This ruled out Svelte (a compiler by construction,
whatever its dev-time speed) and reinforced moving off Angular's Node-based toolchain — a stack
mismatch from the JVM server that had already caused a real CI break earlier (a Node version pin
issue). Landed on:

- **Alpine.js** + **htmx**, vendored locally (no live CDN dependency at runtime — consistent with
  the platform's general vendoring stance).
- **Native Web Components** (`class extends HTMLElement`, `customElements.define`) for structural
  modularity — a top-level nav, a schema-editor, a search screen, a reusable property-table — since
  Alpine's own `Alpine.data()`/`Alpine.store()` don't give the same degree of componentization.
  These are **light DOM** (no shadow root), a deliberate choice so Alpine directives and global CSS
  both still reach inside them normally.
- **Google Material Web** components (buttons, outlined selects), vendored via a custom recursive
  dependency-resolver script rather than the raw npm bundle (which pulls in ~86 unresolved relative
  imports) — it fetches jsdelivr's per-component `+esm` bundles and recursively rewrites/re-fetches
  their transitive imports (Lit, tslib, etc.) into local files, verified to have zero remaining
  live `/npm/...` references. Re-themed via CSS custom properties (which pierce shadow DOM), since
  the library's light-mode baseline (purple primary, literal `'Roboto'` typeface with no fallback)
  didn't match the app's own dark theme/font.
- Any future server-side logic HTMX needs would be Groovy or JavaScript (JSR223, Section 5) rather
  than compiled Java — not yet needed, since nothing server-rendered exists in this UI yet.

### Architecture

- `components/<name>/<name>.js` — one folder per component (room to grow into multiple class files
  per component, e.g. if a screen needs sub-pieces later).
- CSS lives next to the component that owns it: each component file calls a shared
  `injectStyles(id, css)` helper (`components/inject-styles.js`) that appends a `<style>` tag to
  `document.head`, guarded by id so it's idempotent. This mirrors Angular's per-component
  stylesheets despite there being no shadow-root encapsulation — everything still lands in one
  global cascade, just organized by ownership instead of centralized in `index.html`. `index.html`
  itself keeps only genuinely cross-cutting CSS: design tokens (incl. the Material token
  overrides), the page reset/`#app` shell layout, the router's visibility rules, and one truly
  shared utility class (`.status`, used by three different components).
- Client-side hash router (`#/schema`, `#/search`, `#/users`), defaulting to `/search` — reflected
  in the nav's active-tab highlighting and in tab order (Search, Schema, Users, matching Angular's
  own tab order). All three route targets stay mounted once first shown, so each screen's fetched
  state survives switching tabs.

### Screens built so far

- **Schema** — sidebar (item types + traits) and a detail panel (traits/properties/links),
  including the Links panel's nested per-link properties table (cross-referenced via a separate
  top-level `links` array keyed by `linkId`, matching the admin schema API's actual shape). Uses a
  shared `<ntrloc-property-table>` component for both the item/trait's own properties and each
  link's nested properties. Property values are still read-only display (Angular's version has them
  as live-editable inputs) — a known, not-yet-closed gap.
- **Search** — multi-pane grid (add/close/minimize/maximize/restore), Material `md-outlined-select`
  for item-type and sort-field selection, hits `/api/entity/projection` directly. Pane reordering uses
  native HTML5 drag-and-drop: the whole pane (not just its header) is the drag source, live
  reordering during drag is done by updating each pane's CSS `order` (not moving DOM nodes, which
  would abort an in-progress native drag), the dragged pane itself renders as an empty dashed
  placeholder matching Angular CDK's drag-placeholder look, and a cancelled drag (dropped outside
  any target) correctly reverts since the reorder is only committed to the real pane array on an
  actual `drop`. Maximize overlays the pane on top of the others via `position: absolute` +
  `z-index` (not grid spanning, which doesn't actually cover anything); restore just removes the
  overlay, since the pane never left its grid slot underneath.
- **Users** — still a stub ("Users screen coming soon"), matching the Angular app's own stub state
  there.

### A real backend bug found and fixed along the way

Building the Search screen surfaced a genuine, pre-existing gap, unrelated to the new UI itself:
`RegisterInitializer` only ever created each item/link type's dedicated properties table
(`register_item_<uuid>` / `register_link_<uuid>`) once, at boot, from whatever schema already
existed then. Any item/link type added later via the schema editor never got a table, so
projecting it crashed (`relation ... does not exist`) — projection had likely never worked for a
dynamically-added type. Fixed per direction from this session: `SchemaManager` now publishes a
`SchemaChangeEvent` (`ItemTypeCreated/Deleted`, `LinkTypeCreated/Deleted`, `TraitCreated/Deleted`)
via Spring's `ApplicationEventPublisher` after each successful schema mutation;
`RegisterPartitionManager` implements a `SchemaChangeListener` (`@EventListener`) that
creates/drops the corresponding table in response (traits intentionally no-op — their properties
live in the owning item type's JSONB blob, no dedicated table of their own). Also implemented a
previously entirely-missing `DELETE_ITEM` mutation case, discovered in the same pass.

### Pending / deferred

- Users screen — real implementation.
- Editable property values in the schema editor (currently read-only display).
- Delete-row affordances and live cardinality/usage dropdowns in the schema editor's tables
  (currently static text, unlike Angular's editable comboboxes).
- JSR223 dynamic controllers — unchanged from Section 5, still conceptual only.
- Server-rendered module hosting convention (Section 3, mechanism 2) — still just named, not
  designed in detail.

---

*Document generated from ntrloc design session — July 2026. Section 9 added after building the new
admin UI's Schema and Search screens.*
