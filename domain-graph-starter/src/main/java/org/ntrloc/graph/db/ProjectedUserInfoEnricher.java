package org.ntrloc.graph.db;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.projection.ProjectedItem;
import org.ntrloc.graph.projection.ProjectedLink;
import org.ntrloc.graph.projection.ProjectionResult;
import org.ntrloc.graph.projection.UserInfo;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Lives at the top level, same reason LedgerPropertyHistoryService does: it crosses partition
// boundaries (register + security) that RegisterPartitionManager's own rule -- never import the
// security partition, directly or via an injected resolver -- was never meant to extend to.
// RegisterPartitionManager populates every ProjectedItem/ProjectedLink's createdByUserId/
// updatedByUserId (plain UUIDs) but leaves createdBy/updatedBy null; this is the one place that
// resolves them into real UserInfo, batched once per call across the whole tree (every item,
// every link, every linked item, however deeply nested), never one lookup per node. Called from
// EntityManagerImpl right before a projection result reaches its caller -- the only three places
// that construct a ProjectedItem/ProjectionResult the outside world ever sees.
@Component
public class ProjectedUserInfoEnricher {

    private final SecurityRepository securityRepository;

    public ProjectedUserInfoEnricher(SecurityRepository securityRepository) {
        this.securityRepository = securityRepository;
    }

    public Optional<ProjectedItem> enrich(Optional<ProjectedItem> item) {
        return item.map(this::enrich);
    }

    public ProjectedItem enrich(ProjectedItem item) {
        return rewrite(item, resolveAll(List.of(item)));
    }

    public ProjectionResult enrich(ProjectionResult result) {
        Map<UUID, UserInfo> userInfoById = resolveAll(result.items());
        return new ProjectionResult(
                result.items().stream().map(item -> rewrite(item, userInfoById)).toList(),
                result.totalCount(), result.facetedCount(), result.facets(), result.stateMachineFacets());
    }

    private Map<UUID, UserInfo> resolveAll(List<ProjectedItem> items) {
        Set<UUID> userIds = new HashSet<>();
        items.forEach(item -> collectUserIds(item, userIds));
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return securityRepository.findUsersByIds(userIds).stream()
                .collect(Collectors.toMap(SecurityRepository.UserRow::id,
                        row -> new UserInfo(row.email())));
    }

    // updatedByUserId is null for an item/link that's never been updated (see
    // RegisterPartitionManager.insertItemRow's own comment) -- added defensively, not because
    // HashSet rejects null, but because that null would otherwise ride along into
    // SecurityRepository.findUsersByIds' own "WHERE id IN (:ids)" binding.
    private void collectUserIds(ProjectedItem item, Set<UUID> acc) {
        addIfPresent(acc, item.createdByUserId());
        addIfPresent(acc, item.updatedByUserId());
        item.links().values().forEach(links -> links.forEach(link -> {
            addIfPresent(acc, link.createdByUserId());
            addIfPresent(acc, link.updatedByUserId());
            collectUserIds(link.item(), acc);
        }));
    }

    private void addIfPresent(Set<UUID> acc, @Nullable UUID id) {
        if (id != null) {
            acc.add(id);
        }
    }

    private ProjectedItem rewrite(ProjectedItem item, Map<UUID, UserInfo> userInfoById) {
        Map<String, List<ProjectedLink>> rewrittenLinks = item.links().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getValue().stream().map(link -> rewrite(link, userInfoById)).toList()));
        return new ProjectedItem(item.itemId(), item.itemType(), item.properties(), rewrittenLinks, item.states(), item.permissions(),
                item.displayLabel(), item.markers(),
                item.createdDate(), item.createdByUserId(), userInfoById.get(item.createdByUserId()),
                item.updatedDate(), item.updatedByUserId(), userInfoById.get(item.updatedByUserId()));
    }

    private ProjectedLink rewrite(ProjectedLink link, Map<UUID, UserInfo> userInfoById) {
        return new ProjectedLink(link.linkId(), link.properties(), rewrite(link.item(), userInfoById), link.permissions(),
                link.createdDate(), link.createdByUserId(), userInfoById.get(link.createdByUserId()),
                link.updatedDate(), link.updatedByUserId(), userInfoById.get(link.updatedByUserId()));
    }
}
