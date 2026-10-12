package org.ntrloc.graph.access;

import java.util.List;
import java.util.UUID;

// A carrier block's own point of contact with the signal domain -- zero or more condition pin ids
// (whether each sits standalone at the pipeline's top level or nested inside a ConditionGroup),
// combined by quantity into the single boolean that decides whether the block fires. Always a real
// list, never a single id: a gate was always going to be able to combine more than one condition
// directly (see UNCONDITIONAL below for why a single id is just the trivial case of this, not a
// separate shape), without needing a TotalityPredicate wrapping them purely to produce one combined
// pin -- that mechanism solves a different problem (see Quantity's own comment).
//
// conditionOutputPinIds empty means unconditional -- always fires, regardless of quantity (quantity is
// meaningless with nothing to combine, so it's never consulted in that case; this is a deliberate
// special case, not vacuous-ALL/vacuous-ANY truth, to keep "no gate" unambiguous no matter what
// quantity happens to be set to).
public record Gate(List<UUID> conditionOutputPinIds, Quantity quantity) {

    public static final Gate UNCONDITIONAL = new Gate(List.of(), Quantity.ALL);

    public Gate {
        conditionOutputPinIds = conditionOutputPinIds == null ? List.of() : List.copyOf(conditionOutputPinIds);
        quantity = quantity == null ? Quantity.ALL : quantity;
    }

    public static Gate of(UUID conditionOutputPinId) {
        return new Gate(List.of(conditionOutputPinId), Quantity.ALL);
    }

    public boolean isUnconditional() {
        return conditionOutputPinIds.isEmpty();
    }
}
