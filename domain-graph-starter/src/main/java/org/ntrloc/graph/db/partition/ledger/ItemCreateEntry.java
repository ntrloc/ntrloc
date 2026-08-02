package org.ntrloc.graph.db.partition.ledger;

import java.util.Map;
import java.util.UUID;

// properties is keyed by property id (schema_property.id), never by name -- names are mutable
// (UpdatePropertyDefinitionMutation can rename a property), so a name-keyed entry would silently
// disconnect from what it actually refers to after a rename. The ledger must survive that.
public record ItemCreateEntry(UUID itemId, UUID itemTypeId, Map<UUID, Object> properties) implements LedgerEntry {
}
