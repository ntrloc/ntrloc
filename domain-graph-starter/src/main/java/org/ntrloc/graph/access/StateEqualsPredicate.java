package org.ntrloc.graph.access;

import java.util.UUID;

// Compares the current state, by id, within whatever state machine the enclosing StepToStateMachine
// context named -- the context names the machine, this names the state, deliberately kept separate
// (see StepToStateMachine).
public record StateEqualsPredicate(UUID stateId) implements StateMachinePredicate {
}
