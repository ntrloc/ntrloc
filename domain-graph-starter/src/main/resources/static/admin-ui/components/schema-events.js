// One shared EventSource for the whole app rather than a separate connection per component --
// mirrors task-events.js exactly, same rationale (same-origin cookie auth, browser auto-reconnect
// on drop, plain global function rather than an ES module).
//
// The stream itself carries no schema data (see SchemaChangeBroadcaster.java on the backend) --
// it's purely a "something changed, go re-fetch" signal. Every subscriber gets every event,
// including one published as a result of this same browser's own edit (see
// SchemaChangeBroadcaster's own comment on why); what to do about it is entirely up to the caller.

let schemaEventSource = null;
const schemaEventListeners = new Set();

function onSchemaEvent(listener) {
  if (!schemaEventSource) {
    schemaEventSource = new EventSource('/api/admin/schema/events');
    schemaEventSource.addEventListener('message', (event) => {
      schemaEventListeners.forEach((l) => l(event.data));
    });
  }
  schemaEventListeners.add(listener);
  return () => schemaEventListeners.delete(listener);
}
