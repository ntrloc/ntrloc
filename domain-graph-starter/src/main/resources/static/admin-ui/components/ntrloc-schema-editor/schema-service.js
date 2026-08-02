// The "delegate" -- all HTTP transport for the schema editor lives here, mirroring the Angular
// reference implementation's SchemaService (injected there as `delegate` into SchemaModel).
// schema-model.js and schema-view-model.js call into this; nothing else should call these URLs
// directly.
const schemaService = {
  async getAdminSchema() {
    const response = await fetch('/api/admin/schema', { credentials: 'include' });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
    return response.json();
  },

  async applyMutations(mutations) {
    const response = await fetch('/api/admin/schema/mutations', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(mutations),
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
  },

  async getControlledList(propertyId) {
    const response = await fetch(`/api/admin/schema/properties/${encodeURIComponent(propertyId)}/controlled-list`, {
      credentials: 'include',
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
    return response.json();
  },

  // Used to populate the entry/exit/transition/init process pickers in the states editor. Not
  // schema data itself (it's Flowable's own deployed-definitions list), but fetched alongside the
  // schema since that's the only place these ids are referenced from.
  async getProcessDefinitions() {
    const response = await fetch('/api/admin/process/definitions', { credentials: 'include' });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
    return response.json();
  },
};
