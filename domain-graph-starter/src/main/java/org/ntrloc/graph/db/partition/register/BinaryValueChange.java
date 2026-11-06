package org.ntrloc.graph.db.partition.register;

import java.util.Map;
import java.util.UUID;

/**
 * An item's binary property values (binary id by property id) before and after one commit or delete.
 * The register writes these rows; the coordinator uses this to keep the binary dynamic-property registry in step.
 */
public record BinaryValueChange(Map<UUID, UUID> before, Map<UUID, UUID> after) {

    public static final BinaryValueChange NONE = new BinaryValueChange(Map.of(), Map.of());

    public BinaryValueChange {
        before = Map.copyOf(before);
        after = Map.copyOf(after);
    }
}
