package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Context has no behavior of its own yet -- these just pin down the shape: Self is the empty/root
// context, the three steppable variants recurse via `next`, and StepToStateMachine really can't be
// constructed with a continuation (there's no field for one, so that's a compile error, not a test).
class ContextTest {

    @Test
    void selfIsTheEmptyRootContext() {
        assertThat(new Self()).isEqualTo(new Self());
    }

    @Test
    void stepsChainRecursivelyViaNext() {
        UUID photos = UUID.randomUUID();
        UUID portfolios = UUID.randomUUID();

        Context chain = new StepToItem(photos, true,
                new StepToItem(portfolios, true, new Self()));

        assertThat(chain).isInstanceOf(StepToItem.class);
        StepToItem outer = (StepToItem) chain;
        assertThat(outer.linkPerspectiveId()).isEqualTo(photos);
        assertThat(outer.each()).isTrue();
        assertThat(outer.next()).isInstanceOf(StepToItem.class);

        StepToItem inner = (StepToItem) outer.next();
        assertThat(inner.linkPerspectiveId()).isEqualTo(portfolios);
        assertThat(inner.next()).isEqualTo(new Self());
    }

    @Test
    void stateMachineStepIsStructurallyTerminal() {
        // StepToStateMachine has no `next` field at all -- nothing to assert here beyond construction;
        // the absence of a continuation is enforced by the type, not by a runtime check.
        UUID approval = UUID.randomUUID();
        Context terminal = new StepToStateMachine(approval);

        assertThat(terminal).isInstanceOf(StepToStateMachine.class);
        assertThat(((StepToStateMachine) terminal).stateMachineId()).isEqualTo(approval);
    }

    @Test
    void equalChainsAreStructurallyEqual() {
        UUID propertyId = UUID.randomUUID();

        Context a = new StepToProperty(propertyId, false, new Self());
        Context b = new StepToProperty(propertyId, false, new Self());

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void eachFlagIsPartOfEqualityButNotIdentity() {
        UUID linkId = UUID.randomUUID();

        Context iterated = new StepToLink(linkId, true, new Self());
        Context flattened = new StepToLink(linkId, false, new Self());

        assertThat(iterated).isNotEqualTo(flattened);
    }
}
