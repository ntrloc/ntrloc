package org.ntrloc.graph.mutation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.springframework.stereotype.Component;

// Turns a link create's two endpoint references (an item by refId or id, plus a perspective name)
// into concrete endpoints and the one link type both perspectives share. Problems are added to the
// caller's error list, in MutationRequestProcessor's accumulate-everything style.
@Component
class LinkEndpointResolver {

    record ResolvedLink(UUID linkTypeId, LinkEndpoint first, LinkEndpoint second) {}

    private final SchemaManager schemaManager;
    private final RegisterPartitionManager registerPartitionManager;

    LinkEndpointResolver(SchemaManager schemaManager, RegisterPartitionManager registerPartitionManager) {
        this.schemaManager = schemaManager;
        this.registerPartitionManager = registerPartitionManager;
    }

    // refIdToItemId/refIdToItemTypeId: the items created earlier in the same request, by refId.
    Optional<ResolvedLink> resolve(LinkCreateMutation m, String path, Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId,
                                   List<ValidationError> errors) {
        Optional<ResolvedEndpoint> a = resolveEndpoint(m.firstItem(), path + ".firstItem", refIdToItemId, refIdToItemTypeId, errors);
        Optional<ResolvedEndpoint> b = resolveEndpoint(m.secondItem(), path + ".secondItem", refIdToItemId, refIdToItemTypeId, errors);
        if (a.isEmpty() || b.isEmpty()) return Optional.empty();

        return resolveLinkTypeId(a.get(), b.get(), path, errors).map(linkTypeId -> new ResolvedLink(linkTypeId,
                new LinkEndpoint(a.get().perspectiveIdFor(linkTypeId), a.get().itemId()),
                new LinkEndpoint(b.get().perspectiveIdFor(linkTypeId), b.get().itemId())));
    }

    private record ResolvedEndpoint(UUID itemId, String perspectiveName, List<AdminItemLinkPerspectiveView> candidates) {
        Set<UUID> candidateLinkTypeIds() {
            return candidates.stream().map(AdminItemLinkPerspectiveView::linkId).collect(Collectors.toSet());
        }

        UUID perspectiveIdFor(UUID linkTypeId) {
            return candidates.stream()
                    .filter(c -> c.linkId().equals(linkTypeId))
                    .map(AdminItemLinkPerspectiveView::id)
                    .findFirst()
                    .orElseThrow();
        }
    }

    private Optional<ResolvedEndpoint> resolveEndpoint(LinkEndpointReference ref, String path,
                                                         Map<String, UUID> refIdToItemId, Map<String, UUID> refIdToItemTypeId,
                                                         List<ValidationError> errors) {
        UUID itemId;
        UUID itemTypeId;
        if (ref.item() instanceof NewItemReference r) {
            itemId = refIdToItemId.get(r.refId());
            itemTypeId = refIdToItemTypeId.get(r.refId());
            if (itemId == null) {
                errors.add(new ValidationError(path + ".item.refId", "Unknown refId: " + r.refId()));
                return Optional.empty();
            }
        } else if (ref.item() instanceof ExistingItemReference r) {
            Optional<UUID> resolved = registerPartitionManager.findItemTypeId(r.itemId());
            if (resolved.isEmpty()) {
                errors.add(new ValidationError(path + ".item.itemId", "Unknown item: " + r.itemId()));
                return Optional.empty();
            }
            itemId = r.itemId();
            itemTypeId = resolved.get();
        } else {
            throw new IllegalArgumentException("Unsupported item reference: " + ref.item().getClass().getSimpleName());
        }

        List<AdminItemLinkPerspectiveView> candidates = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemTypeId))
                .findFirst()
                .map(item -> item.links().getOrDefault(ref.perspectiveName(), List.of()))
                .orElse(List.of());
        if (candidates.isEmpty()) {
            errors.add(new ValidationError(path + ".perspectiveName", "Unknown perspective: " + ref.perspectiveName()));
            return Optional.empty();
        }
        return Optional.of(new ResolvedEndpoint(itemId, ref.perspectiveName(), candidates));
    }

    // The link type both endpoints' perspectives belong to. A perspective name is unique per item
    // type, so each endpoint names one link; the two just have to agree.
    private Optional<UUID> resolveLinkTypeId(ResolvedEndpoint a, ResolvedEndpoint b, String path, List<ValidationError> errors) {
        Set<UUID> common = new HashSet<>(a.candidateLinkTypeIds());
        common.retainAll(b.candidateLinkTypeIds());
        if (common.isEmpty()) {
            errors.add(new ValidationError(path,
                    "No link connects perspective '%s' to perspective '%s'".formatted(a.perspectiveName(), b.perspectiveName())));
            return Optional.empty();
        }
        return Optional.of(common.iterator().next());
    }
}
