// HTTP transport for marker CRUD, mirroring schema-service.js's shape -- kept separate from that
// file since markers aren't part of the schema-mutation batch system (authorization_marker isn't
// a schema_* table; creating a marker is a direct, immediate write, not staged into
// schemaViewModel.collectMutations()).
//
// Two independent consumers now call into this (schema-view-model.js's own marker CRUD, used by
// the schema editor's "Access Markers" panel, and ntrloc-access.js's Markers panel on the Access
// tab) -- each keeps its own local marker list, and this app has no shadow DOM/single-store
// architecture to keep them in sync automatically. onMarkersChange/notifyMarkersChange below is
// the same shared-pub/sub idiom global-schema-model.js already uses for exactly this problem on
// the schema side: any component that writes a marker calls notifyMarkersChange() (done centrally
// here, not left to each caller to remember) and any component displaying markers should
// subscribe and refetch, or a marker created/edited/deleted in one place silently never appears in
// the other until a full page reload.
const markerChangeListeners = new Set();

function onMarkersChange(listener) {
  markerChangeListeners.add(listener);
  return () => markerChangeListeners.delete(listener);
}

function notifyMarkersChange() {
  markerChangeListeners.forEach((listener) => listener());
}

const markerService = {
  async getMarkers() {
    const response = await fetch('/api/admin/markers', { credentials: 'include' });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
    return response.json();
  },

  async getMarkerRules() {
    const response = await fetch('/api/admin/markers/rules', { credentials: 'include' });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
    return response.json();
  },

  // Enable/disable toggling isn't here yet -- see MarkerAdminController's own comment.
  async createMarkerRule({ name, itemTypeId, decisionKey }) {
    const response = await fetch('/api/admin/markers/rules', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, itemTypeId, decisionKey }),
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
    return response.json();
  },

  async deleteMarkerRule(id) {
    const response = await fetch(`/api/admin/markers/rules/${encodeURIComponent(id)}`, {
      method: 'DELETE',
      credentials: 'include',
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
  },

  async createMarker({ name, description, scopeKind, scopeId }) {
    const response = await fetch('/api/admin/markers', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description, scopeKind, scopeId }),
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
    const marker = await response.json();
    notifyMarkersChange();
    return marker;
  },

  async updateMarker(id, { name, description }) {
    const response = await fetch(`/api/admin/markers/${id}`, {
      method: 'PUT',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description }),
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
    const marker = await response.json();
    notifyMarkersChange();
    return marker;
  },

  async deleteMarker(id) {
    const response = await fetch(`/api/admin/markers/${id}`, {
      method: 'DELETE',
      credentials: 'include',
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({ message: 'Request failed: ' + response.status }));
      throw new Error(error.message || 'Request failed: ' + response.status);
    }
    notifyMarkersChange();
  },
};
