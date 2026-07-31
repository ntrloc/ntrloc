// One shared EventSource for the whole app rather than a separate connection per component
// (ntrloc-nav's badge and ntrloc-tasks's list both need to react to the same signal). Same-origin
// request, so the session cookie is sent automatically -- no credentials config needed for
// EventSource, unlike fetch(). The browser's own EventSource reconnects on drop with no extra code
// here. Plain global function (see inject-styles.js) rather than an ES module, matching this app's
// other first-party components -- type="module" is reserved for genuine vendored package source
// (diagram-js, bpmn-moddle) per ntrloc-process-editor.js's own note in index.html.
//
// The stream itself carries no task data (see TaskEventBroadcaster.java on the backend) -- it's
// purely a "something changed, go re-fetch" signal. Every subscriber gets every event; what to do
// about it (refetch a count, refetch a list) is entirely up to the caller.

let taskEventSource = null;
const taskEventListeners = new Set();

function onTaskEvent(listener) {
  if (!taskEventSource) {
    taskEventSource = new EventSource('/api/admin/process/tasks/events');
    taskEventSource.addEventListener('message', (event) => {
      taskEventListeners.forEach((l) => l(event.data));
    });
  }
  taskEventListeners.add(listener);
  return () => taskEventListeners.delete(listener);
}
