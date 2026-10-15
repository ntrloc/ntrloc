package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ContextResolverTest {

    @Test
    void resolvesAPropertyStepTerminatingInSelfToThatProperty_sValue() {
        UUID propertyId = UUID.randomUUID();
        Context context = new StepToProperty(propertyId, false, new Self());

        Object value = ContextResolver.resolve(context, Map.of(propertyId, "OUT_OF_STOCK"));

        assertThat(value).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void resolvesToNullWhenThePropertyIsAbsentFromTheMap() {
        UUID propertyId = UUID.randomUUID();
        Context context = new StepToProperty(propertyId, false, new Self());

        Object value = ContextResolver.resolve(context, Map.of());

        assertThat(value).isNull();
    }

    @Test
    void anEachStep_isNotYetSupported() {
        UUID propertyId = UUID.randomUUID();
        Context context = new StepToProperty(propertyId, true, new Self());

        assertThatThrownBy(() -> ContextResolver.resolve(context, Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aLinkTraversalStep_isNotYetSupported() {
        Context context = new StepToLink(UUID.randomUUID(), false, new Self());

        assertThatThrownBy(() -> ContextResolver.resolve(context, Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aBareSelfContextWithNoSteps_isNotYetSupported() {
        assertThatThrownBy(() -> ContextResolver.resolve(new Self(), Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- resolveInstances: multi-instance traversal ---

    private final UUID rootId = UUID.randomUUID();
    private final UUID perspectiveId = UUID.randomUUID();

    @Test
    void resolveInstances_aBareSelfContext_resolvesToTheCurrentItemsOwnId() {
        List<ContextResolver.Instance> instances = ContextResolver.resolveInstances(
                new Self(), rootId, Map.of(), new InMemoryFactResolver());

        assertThat(instances).containsExactly(new ContextResolver.Instance(rootId, rootId));
    }

    @Test
    void resolveInstances_aPropertyStepTerminatingInSelf_behavesLikeTheSingleValueResolve() {
        UUID propertyId = UUID.randomUUID();
        Context context = new StepToProperty(propertyId, false, new Self());

        List<ContextResolver.Instance> instances = ContextResolver.resolveInstances(
                context, rootId, Map.of(propertyId, "OUT_OF_STOCK"), new InMemoryFactResolver());

        assertThat(instances).containsExactly(new ContextResolver.Instance(rootId, "OUT_OF_STOCK"));
    }

    @Test
    void resolveInstances_anEachStepOverATwoItemLink_producesOneInstancePerLinkedItem() {
        UUID photoA = UUID.randomUUID();
        UUID photoB = UUID.randomUUID();
        FactResolver facts = new InMemoryFactResolver().link(rootId, perspectiveId, photoA).link(rootId, perspectiveId, photoB);
        Context context = new StepToItem(perspectiveId, true, new Self());

        List<ContextResolver.Instance> instances = ContextResolver.resolveInstances(context, rootId, Map.of(), facts);

        assertThat(instances).extracting(ContextResolver.Instance::itemId).containsExactlyInAnyOrder(photoA, photoB);
    }

    @Test
    void resolveInstances_anEachStepOverAnEmptyLink_producesNoInstances_ratherThanThrowing() {
        Context context = new StepToItem(perspectiveId, true, new Self());

        List<ContextResolver.Instance> instances = ContextResolver.resolveInstances(
                context, rootId, Map.of(), new InMemoryFactResolver());

        assertThat(instances).isEmpty();
    }

    @Test
    void resolveInstances_twoNestedEachSteps_producesTheCrossProductOfBothLevels() {
        // Mirrors the real Photographer pipeline: self -> photos (each) -> portfolios (each) ->
        // state machine. Two photos, one with two portfolios and one with one, should produce
        // 2 + 1 = 3 leaf instances, each carrying that specific portfolio's own current state.
        UUID photosPerspective = UUID.randomUUID();
        UUID portfoliosPerspective = UUID.randomUUID();
        UUID stateMachineId = UUID.randomUUID();
        UUID publishedStateId = UUID.randomUUID();
        UUID draftStateId = UUID.randomUUID();
        UUID photoA = UUID.randomUUID();
        UUID photoB = UUID.randomUUID();
        UUID portfolio1 = UUID.randomUUID();
        UUID portfolio2 = UUID.randomUUID();
        UUID portfolio3 = UUID.randomUUID();

        FactResolver facts = new InMemoryFactResolver()
                .link(rootId, photosPerspective, photoA)
                .link(rootId, photosPerspective, photoB)
                .link(photoA, portfoliosPerspective, portfolio1)
                .link(photoA, portfoliosPerspective, portfolio2)
                .link(photoB, portfoliosPerspective, portfolio3)
                .state(portfolio1, stateMachineId, publishedStateId)
                .state(portfolio2, stateMachineId, draftStateId)
                .state(portfolio3, stateMachineId, publishedStateId);

        Context context = new StepToItem(photosPerspective, true,
                new StepToItem(portfoliosPerspective, true, new StepToStateMachine(stateMachineId)));

        List<ContextResolver.Instance> instances = ContextResolver.resolveInstances(context, rootId, Map.of(), facts);

        assertThat(instances)
                .extracting(ContextResolver.Instance::itemId, ContextResolver.Instance::value)
                .containsExactlyInAnyOrder(
                        tuple(portfolio1, publishedStateId),
                        tuple(portfolio2, draftStateId),
                        tuple(portfolio3, publishedStateId));
    }

    @Test
    void resolveInstances_aDeterministicStepWithExactlyOneTarget_continuesWithoutEach() {
        UUID author = UUID.randomUUID();
        FactResolver facts = new InMemoryFactResolver().link(rootId, perspectiveId, author)
                .properties(author, Map.of());
        Context context = new StepToItem(perspectiveId, false, new Self());

        List<ContextResolver.Instance> instances = ContextResolver.resolveInstances(context, rootId, Map.of(), facts);

        assertThat(instances).containsExactly(new ContextResolver.Instance(author, author));
    }

    @Test
    void resolveInstances_aDeterministicStepWithNoTargets_producesNoInstances() {
        Context context = new StepToItem(perspectiveId, false, new StepToStateMachine(UUID.randomUUID()));

        List<ContextResolver.Instance> instances = ContextResolver.resolveInstances(
                context, rootId, Map.of(), new InMemoryFactResolver());

        assertThat(instances).isEmpty();
    }

    @Test
    void resolveInstances_aDeterministicStepWithMoreThanOneTarget_isNotYetSupported() {
        UUID photoA = UUID.randomUUID();
        UUID photoB = UUID.randomUUID();
        FactResolver facts = new InMemoryFactResolver().link(rootId, perspectiveId, photoA).link(rootId, perspectiveId, photoB);
        Context context = new StepToItem(perspectiveId, false, new Self());

        assertThatThrownBy(() -> ContextResolver.resolveInstances(context, rootId, Map.of(), facts))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resolveInstances_aLinkTraversalStep_isNotYetSupported() {
        Context context = new StepToLink(perspectiveId, false, new Self());

        assertThatThrownBy(() -> ContextResolver.resolveInstances(context, rootId, Map.of(), new InMemoryFactResolver()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
