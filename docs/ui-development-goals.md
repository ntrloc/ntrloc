# NTRLOC UI Development Goals

## Overview

The `domain-graph-experimental-ui` module is an administrative web application for the NTRLOC platform, built with Angular 21 and Angular Material. It is developed in parallel with the back-end service (`domain-graph-experimental`) using a vertical slice approach to ensure the full stack remains coherent at each stage of development.

## Guiding Principles

### Vertical Slices
Each feature is developed end-to-end — UI, API contract, service layer, and persistence — rather than completing the back end in isolation before addressing the front end. This surfaces mismatches between the UI's needs and the API's design early, when they are cheap to fix.

### Separation of Concerns (MVVM)
The front end follows an MVVM-style separation:
- **Model** — TypeScript interfaces mirroring back-end domain objects, located in `model/` subdirectories within each feature
- **View** — Component templates (`.html` files), kept thin and driven by bindings
- **ViewModel (Component)** — Component classes (`.ts` files) that mediate between services and templates; contain no business logic
- **Service** — Angular services own all HTTP communication and shared state; injected into parent components only, with data passed to child components via `@Input()`

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
