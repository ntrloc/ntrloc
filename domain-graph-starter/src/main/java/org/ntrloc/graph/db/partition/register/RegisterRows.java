package org.ntrloc.graph.db.partition.register;

import org.springframework.lang.Nullable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** What RegisterStore returns for an {@link ExplicitProjection}: committed rows, keyed by id, with no names resolved. */
public record RegisterRows(List<ItemRow> items) {

    public RegisterRows {
        items = List.copyOf(items);
    }

    /**
     * One committed item row.
     *
     * @param properties      raw property values by property id (binary properties are not here; they live in their own table)
     * @param currentStateIds current state id by state machine id, for each machine the item is in
     */
    public record ItemRow(UUID registerItemId, UUID itemId, UUID itemTypeId,
                          Map<UUID, Object> properties, Map<UUID, UUID> currentStateIds, Audit audit) {}

    public record Audit(OffsetDateTime createdDate, UUID createdBy,
                        @Nullable OffsetDateTime updatedDate, @Nullable UUID updatedBy) {}
}
