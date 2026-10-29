package org.ntrloc.graph.db.partition.schema.definition.mutation;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

// Holds a batch to the controlled list being as its editor last saw it: the name, and the values in
// order when the editor had them (null skips that check). Otherwise the batch fails with a conflict
// rather than overwriting someone else's change -- as ExpectItemDefinitionMutation does for an item type.
public record ExpectControlledListMutation(UUID listId, String name, @Nullable List<UpdateControlledListMutation.Entry> values)
        implements DefinitionMutation {
}
