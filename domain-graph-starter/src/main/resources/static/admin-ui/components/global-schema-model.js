// The shared, app-wide "Model" for raw schema data -- distinct from ntrloc-schema-editor's own
// schemaModel/schemaViewModel pair (that pair carries editor-only state: dirty tracking, pending
// mutations, selection -- not a fit for a read-only consumer like a search pane or the New Item
// dialog). Named distinctly (globalSchemaModel, not schemaModel) since both are plain globals
// loaded on the same page -- a same-named const would silently collide. Plain global function
// rather than an ES module, matching this app's other first-party components (see task-events.js).
//
// Combines Model (cache) and a minimal ViewModel (pub/sub) in one object, unlike the editor's own
// split -- there's no dirty-tracking/mutation-building concern here to warrant a separate layer.
//
// Auto-refreshes on every onSchemaEvent signal (see schema-events.js), including one caused by
// this same browser's own edit (SchemaChangeBroadcaster doesn't filter those out), so any
// subscriber sees the current schema without polling or manually invalidating.

const globalSchemaModelListeners = new Set();

function onGlobalSchemaChange(listener) {
  globalSchemaModelListeners.add(listener);
  return () => globalSchemaModelListeners.delete(listener);
}

function notifyGlobalSchemaChange() {
  globalSchemaModelListeners.forEach((listener) => listener());
}

const globalSchemaModel = {
  _schema: null,

  // Returns the cached schema if already loaded, otherwise fetches (and caches) it. Callers that
  // want a guaranteed-fresh fetch (e.g. self-healing against a missed SSE event on pane/dialog
  // open) should call reload() instead.
  load() {
    if (this._schema) return Promise.resolve(this._schema);
    return this._fetch();
  },

  reload() {
    return this._fetch();
  },

  async _fetch() {
    const response = await fetch('/api/admin/schema', { credentials: 'include' });
    if (!response.ok) {
      throw new Error('Request failed: ' + response.status);
    }
    this._schema = await response.json();
    notifyGlobalSchemaChange();
    return this._schema;
  },
};

onSchemaEvent(() => {
  globalSchemaModel.reload().catch(() => {
    // Left silently empty, mirroring ntrloc-search.js's own loadItemTypes() error handling -- a
    // failed background refresh just leaves the previously cached schema in place.
  });
});
