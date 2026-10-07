package org.ntrloc.graph.access;

import java.util.UUID;

// Step to a state machine. Structurally terminal -- no `next` field, so a state machine leading
// somewhere else is impossible to construct rather than a rule enforced at validation time. Names
// the whole state machine, not a specific state; the actual value comparison belongs entirely to the
// predicate that reads this context.
public record StepToStateMachine(UUID stateMachineId) implements Context {
}
