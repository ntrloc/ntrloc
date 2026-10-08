package org.ntrloc.graph.access;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
