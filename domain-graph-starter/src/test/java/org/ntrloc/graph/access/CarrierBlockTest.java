package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

// Pins down the carrier side from "Marker actions: carriers and virtual blocks": AddMarkers/
// RemoveMarkers/SetMarkers need at least 1 marker and an optional gate; Filter has no markers at all
// and a mandatory signal; Start/End are pure source/sink; Route is the one place carrier fan-out/
// fan-in connectivity lives, separate from the (tree-shaped) block definitions themselves.
class CarrierBlockTest {

    @Test
    void addMarkersRequiresAtLeastOneMarker() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AddMarkers(UUID.randomUUID(), null, List.of()));
    }

    @Test
    void addMarkersGateIsOptional() {
        AddMarkers unconditional = new AddMarkers(UUID.randomUUID(), null, List.of(UUID.randomUUID()));
        assertThat(unconditional.gate()).isNull();
    }

    @Test
    void removeMarkersSameShapeAsAddMarkers() {
        RemoveMarkers remove = new RemoveMarkers(UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID()));
        assertThat(remove.markerIds()).hasSize(1);
    }

    @Test
    void setMarkersSameShapeToo() {
        SetMarkers set = new SetMarkers(UUID.randomUUID(), null, List.of(UUID.randomUUID(), UUID.randomUUID()));
        assertThat(set.markerIds()).hasSize(2);
    }

    @Test
    void filterHasNoMarkersAndASignalIsMandatory() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Filter(UUID.randomUUID(), null));
    }

    @Test
    void filterConstructsWithAMandatorySignal() {
        Filter filter = new Filter(UUID.randomUUID(), UUID.randomUUID());
        assertThat(filter.signal()).isNotNull();
    }

    @Test
    void startAndEndAreSingleFieldSourceAndSink() {
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        assertThat(start).isInstanceOf(CarrierBlock.class);
        assertThat(end).isInstanceOf(CarrierBlock.class);
    }

    @Test
    void routeSupportsFanOutFromOneSourceToManyTargets() {
        UUID source = UUID.randomUUID();
        UUID targetA = UUID.randomUUID();
        UUID targetB = UUID.randomUUID();

        Route route = new Route(source, List.of(targetA, targetB));

        assertThat(route.inputPinIds()).containsExactly(targetA, targetB);
    }

    @Test
    void fanInIsJustTwoSeparateRoutesSharingTheSameTarget() {
        // No combining node needed -- implicit carrier merge means two independent Route entries
        // naming the same inputPinId is already a complete description of fan-in.
        UUID end = UUID.randomUUID();
        Route fromA = new Route(UUID.randomUUID(), List.of(end));
        Route fromB = new Route(UUID.randomUUID(), List.of(end));

        assertThat(fromA.inputPinIds()).containsExactly(end);
        assertThat(fromB.inputPinIds()).containsExactly(end);
    }

    @Test
    void routeRequiresAtLeastOneTarget() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new Route(UUID.randomUUID(), List.of()));
    }
}
