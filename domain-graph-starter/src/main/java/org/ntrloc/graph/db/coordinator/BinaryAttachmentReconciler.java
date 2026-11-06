package org.ntrloc.graph.db.coordinator;

import org.ntrloc.graph.db.partition.binary.BinaryDynamicPropertyRegistrar;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.binary.BinaryPropertyObject;
import org.ntrloc.graph.db.partition.register.BinaryValueChange;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// Keeps the binary dynamic-property registry in step with the register's binary property values.
// Per property, a changed value detaches the old binary (if any) and attaches the new one (if any);
// an unchanged value is a no-op. Called by the coordinator inside the same transaction as the
// register write that produced the change.
@Component
class BinaryAttachmentReconciler {

    private final BinaryPartitionManager binaryPartitionManager;
    private final BinaryDynamicPropertyRegistrar binaryDynamicPropertyRegistrar;

    BinaryAttachmentReconciler(BinaryPartitionManager binaryPartitionManager,
                               BinaryDynamicPropertyRegistrar binaryDynamicPropertyRegistrar) {
        this.binaryPartitionManager = binaryPartitionManager;
        this.binaryDynamicPropertyRegistrar = binaryDynamicPropertyRegistrar;
    }

    void reconcile(BinaryValueChange change) {
        Set<UUID> propertyIds = new HashSet<>(change.before().keySet());
        propertyIds.addAll(change.after().keySet());
        for (UUID propertyId : propertyIds) {
            UUID oldBinaryId = change.before().get(propertyId);
            UUID newBinaryId = change.after().get(propertyId);
            if (Objects.equals(oldBinaryId, newBinaryId)) continue;
            if (oldBinaryId != null) {
                binaryDynamicPropertyRegistrar.onDetach(propertyId, metadataFor(oldBinaryId));
            }
            if (newBinaryId != null) {
                binaryDynamicPropertyRegistrar.onAttach(propertyId, metadataFor(newBinaryId));
            }
        }
    }

    private Map<String, Object> metadataFor(UUID binaryId) {
        return binaryPartitionManager.getBinaryProperty(binaryId).map(BinaryPropertyObject::metadata).orElse(null);
    }
}
