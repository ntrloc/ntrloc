// Generic multi-tab bookkeeping for "several documents open, one active at a time" editors
// (BPMN process editor now, DMN decision-table editor later). Same singleton-object +
// listener-Set + notify() shape as schema-view-model.js, except instantiable
// (createTabWorkspaceViewModel()) rather than a bare module-level singleton, since each host
// feature (Processes, a future Decisions screen) needs its own independent set of open tabs
// rather than sharing one app-wide list.
function createTabWorkspaceViewModel() {
  const listeners = new Set();
  function notify() {
    listeners.forEach((listener) => listener());
  }

  return {
    tabs: [],
    activeTabId: null,

    onChange(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },

    get activeTab() {
      return this.tabs.find((t) => t.id === this.activeTabId) ?? null;
    },

    get isDirty() {
      return this.tabs.some((t) => t.isDirty);
    },

    // Opens a new tab for the given resource, or just activates it if already open -- opening the
    // same process definition twice should never produce two tabs onto the same document.
    openTab({ id, title, resourceType }) {
      const existing = this.tabs.find((t) => t.id === id);
      if (existing) {
        this.activeTabId = id;
        notify();
        return existing;
      }
      const tab = { id, title, resourceType, isDirty: false };
      this.tabs = [...this.tabs, tab];
      this.activeTabId = id;
      notify();
      return tab;
    },

    setActiveTab(id) {
      if (this.activeTabId === id) return;
      this.activeTabId = id;
      notify();
    },

    setTabDirty(id, isDirty) {
      const tab = this.tabs.find((t) => t.id === id);
      if (!tab || tab.isDirty === isDirty) return;
      tab.isDirty = isDirty;
      notify();
    },

    setTabTitle(id, title) {
      const tab = this.tabs.find((t) => t.id === id);
      if (!tab || tab.title === title) return;
      tab.title = title;
      notify();
    },

    // Picks the tab that took this one's place in the strip (or its new last neighbor) as the
    // next active tab, matching how browser/IDE tab strips behave on close.
    closeTab(id) {
      const index = this.tabs.findIndex((t) => t.id === id);
      if (index === -1) return;
      const wasActive = this.activeTabId === id;
      this.tabs = this.tabs.filter((t) => t.id !== id);
      if (wasActive) {
        const fallback = this.tabs[index] ?? this.tabs[index - 1] ?? null;
        this.activeTabId = fallback ? fallback.id : null;
      }
      notify();
    },
  };
}
