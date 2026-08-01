package org.ntrloc.graph.db;

import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntryRecord;
import org.ntrloc.graph.db.partition.ledger.LedgerPartitionManager;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Lives at the top level (alongside EntityManager), not inside the ledger package, for the same
// reason EntityManagerImpl does: it crosses partition boundaries (ledger + security) that
// LedgerRegisterCoordinator's own "the only component permitted to import both ledger and
// register" rule was never meant to extend to.
@Service
public class LedgerPropertyHistoryService {

    // displayName is resolved best-effort at read time, not stored -- a user's display name can
    // change after the edit it's shown against, and this always reflects the current one, same as
    // any other "who did this" attribution. Blank (not an error) when actorExternalId is null
    // (LedgerInitializer's own note: a real, displayable state) or no longer resolves to a user.
    public record PropertyHistoryEntry(OffsetDateTime editedOn, String editedByExternalId, String editedByDisplayName, Object value) {
    }

    private final LedgerPartitionManager ledgerPartitionManager;
    private final SecurityRepository securityRepository;

    public LedgerPropertyHistoryService(LedgerPartitionManager ledgerPartitionManager, SecurityRepository securityRepository) {
        this.ledgerPartitionManager = ledgerPartitionManager;
        this.securityRepository = securityRepository;
    }

    // Most-recent-first, matching how an edit-history view is normally read -- readItemStream*
    // itself is oldest-first (sequence_number ascending), the order the register's own
    // materialization needs.
    public List<PropertyHistoryEntry> history(UUID itemId, UUID propertyId) {
        Map<String, String> displayNameCache = new HashMap<>();
        List<PropertyHistoryEntry> entries = ledgerPartitionManager.readItemStreamWithMetadata(itemId).stream()
                .filter(ledgerRecord -> touchesProperty(ledgerRecord.entry(), propertyId))
                .map(ledgerRecord -> toHistoryEntry(ledgerRecord, propertyId, displayNameCache))
                .toList();
        List<PropertyHistoryEntry> reversed = new ArrayList<>(entries);
        Collections.reverse(reversed);
        return reversed;
    }

    private boolean touchesProperty(LedgerEntry entry, UUID propertyId) {
        return propertiesOf(entry).containsKey(propertyId);
    }

    private Map<UUID, Object> propertiesOf(LedgerEntry entry) {
        if (entry instanceof ItemCreateEntry e) return e.properties();
        if (entry instanceof ItemUpdateEntry e) return e.properties();
        return Map.of();
    }

    private PropertyHistoryEntry toHistoryEntry(LedgerEntryRecord ledgerRecord, UUID propertyId, Map<String, String> displayNameCache) {
        Object value = propertiesOf(ledgerRecord.entry()).get(propertyId);
        String actorExternalId = ledgerRecord.actorExternalId();
        String displayName = actorExternalId == null ? null : displayNameCache.computeIfAbsent(actorExternalId, this::resolveDisplayName);
        return new PropertyHistoryEntry(ledgerRecord.createdAt(), actorExternalId, displayName, value);
    }

    private String resolveDisplayName(String externalId) {
        return securityRepository.findUserByExternalId(externalId)
                .map(SecurityRepository.UserRow::displayName)
                .orElse(null);
    }
}
