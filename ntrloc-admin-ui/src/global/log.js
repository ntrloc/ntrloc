// Positive logs of the steps behind a live schema update -- what was sent to the server, what came
// back, what arrived over the event stream and what the schema model did about it -- so a browser
// console (a failed UI test's captured one included) shows where that chain stopped. Plain strings
// only, so they read the same wherever the console is captured. Line breaks are flattened so text
// from the server (an error message, an event type) can't forge extra log lines.
const LINE_BREAKS = /[\r\n\u2028\u2029]+/g

export function logInfo(message) {
  console.info(`[ntrloc] ${String(message).replaceAll(LINE_BREAKS, ' ')}`)
}
