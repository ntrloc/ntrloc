# NTRLOC UI Development Goals

## Overview

The `domain-graph-experimental-ui` module is an administrative web application for the NTRLOC platform, built with Angular 21 and Angular Material. It is developed in parallel with the back-end service (`domain-graph-experimental`) using a vertical slice approach to ensure the full stack remains coherent at each stage of development.

## Guiding Principles

### Vertical Slices
Each feature is developed end-to-end — UI, API contract, service layer, and persistence — rather than completing the back end in isolation before addressing the front end. This surfaces mismatches between the UI's needs and the API's design early, when they are cheap to fix.

### Separation of Concerns (MVC / global model)

The front end is structured around a **global model** — a set of singleton domain models that own all application state and operations. Components are transient; the model survives navigation.

#### Naming conventions

- **Model** (`*Model`) — A singleton Angular service (provided at root) that owns state and exposes operations for a domain. It holds the view model for that domain and is the only thing that mutates it. Components interact with the application exclusively through model instances. The Angular DI mechanism is simply how the singleton is delivered — the name and responsibility are what define it as a model, not the Angular term "service".
- **ViewModel** — Mutable, UI-friendly representation of domain state, held inside the model. Tracks dirty/new/deleted state. Components bind to view model instances directly.
- **Operation Delegate** (`*OperationDelegate`) — A singleton that handles communication with an external system (e.g. HTTP API calls) on behalf of a model. Stateless by design. The model owns the operation conceptually; the delegate handles the mechanical details. Analogous to the JDBC repositories on the back end.
- **View** — Component templates (`.html` files), kept thin and driven by bindings. No logic beyond translating user gestures into model operation calls.
- **Controller** — Component classes (`.ts` files) that translate user gestures into model operation calls. They do not own state, do not call delegates directly, and contain no business logic.

#### Global model structure

An `AppModel` acts as a lightweight umbrella composing the individual domain models. Most components inject only the domain model they need — `SchemaModel` for the schema editor, `SearchModel` for the search view, etc. Cross-domain concerns are handled at the `AppModel` level, but domain models are otherwise independent.

```
AppModel
  ├── SchemaModel          ← owns SchemaViewModel + schema operations
  │     └── SchemaOperationDelegate   ← HTTP calls for schema API
  ├── SearchModel          ← owns search pane state
  └── (future domain models as slices are added)
```

State is never owned by components. Navigating away from a view destroys the component but leaves the model — and all unsaved changes — intact.

### API Reuse
The back-end REST APIs are designed for multiple clients, not exclusively for this UI. The UI reuses general API endpoints by default. Divergence (e.g. a dedicated BFF endpoint) is only justified when aggregation would otherwise require multiple round-trips, or when a UI-specific interaction pattern has no equivalent in other clients.

## Application Structure

### Layout
- **Navigation bar** (80px, always visible): logo (left), Material tab nav (center), settings/menu placeholder (right)
- **Display area** (remaining viewport height, scrollable): renders the active feature component via Angular Router child routes

### Routes
| Path | Component | Description |
|---|---|---|
| `/schema` | `SchemaEditor` | Schema management (default) |
| `/users` | `UserEditor` | User and group management |

## Development Slices (Planned Order)

### 1. Schema Management (`/schema`)
Allows administrators to view and manage the graph schema — item definitions, property definitions, and link definitions.

**Initial scope:** View the current schema (item list + item detail).

**Full scope (to be developed incrementally):**
- View list of item definitions
- View item definition detail (properties, links)
- Create item definition
- Add/remove properties from an item definition
- Rename property
- Create link definition
- Additional schema mutation operations as needed

**API:** `GET /schema` → `SchemaModel` (list of `ItemDefinition` with properties and links)

**Component structure:**
```
schema-editor/
  model/schema.model.ts
  services/schema.service.ts
  item-list/          ← left column: list of item names
  item-detail/        ← center: selected item's properties and links
  schema-editor.ts    ← parent; owns selection state
```

### 2. User & Group Management (`/users`)
Allows administrators to manage users and groups.

*Details to be determined during development of this slice.*

### 3. Graph Mutations
Allows administrators (and eventually other roles) to create, update, and delete graph nodes and edges within the constraints of the schema.

*Details to be determined during development of this slice.*

### 4. Graph Projections
Allows querying and visualising subgraphs — filtered and projected views of the graph data.

*Details to be determined during development of this slice.*

### 5. User & Group Security Permissions
Allows administrators to assign permissions to users and groups, controlling access to schema types, graph mutations, and projections.

*Details to be determined during development of this slice.*

## Technology Stack

| Layer | Technology |
|---|---|
| Framework | Angular 21 (standalone components) |
| Component library | Angular Material 21 |
| Styling | SCSS |
| HTTP | Angular `HttpClient` |
| Routing | Angular Router (child routes) |
| Build (dev) | Angular CLI / Vite dev server (`npm start`) |
| Build (CI/release) | `frontend-maven-plugin` + `ng build` → packaged as JAR |

## Colour Palette & Typography

| Token | Value |
|---|---|
| Background | `#0f1419` |
| Text | `#e6edf3` |
| Font family | `sans-serif` |
| Material colour scheme | Dark |
