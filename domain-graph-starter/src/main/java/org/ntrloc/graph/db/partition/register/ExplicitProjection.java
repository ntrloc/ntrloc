package org.ntrloc.graph.db.partition.register;

import org.ntrloc.graph.db.partition.CorrelatedSql;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A projection already resolved against the schema: every name is an id, every permission is a plain
 * marker condition, and every request error has already been raised. RegisterStore compiles it to SQL
 * against its own tables and trusts it as given. See docs/ntrloc-architecture-rationalization.md §5.1.
 *
 * @param itemTypeIds        the item types whose rows are read (exactly one, until cross-type queries move here)
 * @param filter             which rows match
 * @param access             which rows the caller may see at all
 * @param termsFilters       facet selections; each narrows the rows, and every terms facet but its own property's
 * @param termsFacets        value counts per property, over the filtered rows
 * @param stateMachineFacets item counts per current state, over the filtered rows (terms filters don't apply)
 * @param sort               row order
 * @param page               which rows to return, or null for all
 * @param links              which links to return with each row, nested as deep as requested
 * @param linkAccess         which of those links the caller may see
 * @param count              whether to count the filtered rows; when it does and none match, no rows are read
 */
public record ExplicitProjection(Set<UUID> itemTypeIds, Condition filter, Access access,
                                 List<TermsFilter> termsFilters, List<TermsFacet> termsFacets, List<StateMachineFacet> stateMachineFacets,
                                 Sort sort, @Nullable Page page, LinkSelection links, LinkAccess linkAccess, boolean count) {

    public ExplicitProjection {
        itemTypeIds = Set.copyOf(itemTypeIds);
        if (itemTypeIds.isEmpty()) throw new IllegalArgumentException("An ExplicitProjection needs at least one item type");
        termsFilters = List.copyOf(termsFilters);
        termsFacets = List.copyOf(termsFacets);
        stateMachineFacets = List.copyOf(stateMachineFacets);
    }

    /** One item, by its business id, with no access condition on the item itself (the caller has already checked it). */
    public static ExplicitProjection singleItem(UUID itemTypeId, UUID itemId, LinkSelection links, LinkAccess linkAccess) {
        return new ExplicitProjection(Set.of(itemTypeId), new Condition.ItemIdIs(itemId), Access.Unrestricted.INSTANCE,
                List.of(), List.of(), List.of(), Sort.DEFAULT, null, links, linkAccess, false);
    }

    /**
     * Which links to follow from a row: through these perspectives (null: every perspective), and from
     * each link's target, the nested selection keyed by the perspective it was reached through (none
     * when absent).
     */
    public record LinkSelection(@Nullable Set<UUID> perspectiveIds, Map<UUID, LinkSelection> nested) {
        public static final LinkSelection NONE = new LinkSelection(Set.of(), Map.of());
        public static final LinkSelection ALL_DIRECT = new LinkSelection(null, Map.of());

        public LinkSelection {
            perspectiveIds = perspectiveIds == null ? null : Set.copyOf(perspectiveIds);
            nested = Map.copyOf(nested);
        }

        public boolean isNone() {
            return perspectiveIds != null && perspectiveIds.isEmpty();
        }
    }

    /**
     * Which links the caller may see. All three conditions must hold: the link's source item carries a
     * marker granting read on the perspective followed; the target's item type is readable; and the
     * target carries a marker granting item read. Links failing any are left out, as if absent.
     */
    public sealed interface LinkAccess {
        enum Unrestricted implements LinkAccess { INSTANCE }

        record Granted(Map<UUID, Set<UUID>> readablePerspectiveIdsBySourceMarker, Set<UUID> readableTargetItemTypeIds,
                       Set<UUID> targetItemReadMarkerIds) implements LinkAccess {
            public Granted {
                readablePerspectiveIdsBySourceMarker = Map.copyOf(readablePerspectiveIdsBySourceMarker);
                readableTargetItemTypeIds = Set.copyOf(readableTargetItemTypeIds);
                targetItemReadMarkerIds = Set.copyOf(targetItemReadMarkerIds);
            }
        }
    }

    /** Items whose stored value for the property is one of {@code values}, or absent when {@code includeNull}. Matches everything when both are empty/false. */
    public record TermsFilter(UUID propertyId, List<String> values, boolean includeNull) {
        public TermsFilter { values = List.copyOf(values); }
    }

    public record TermsFacet(String key, UUID propertyId) {}

    public record StateMachineFacet(String key, UUID stateMachineId) {}

    public record Page(int offset, int limit) {}

    public sealed interface Condition {
        /** Every row. */
        enum All implements Condition { INSTANCE }

        record ItemIdIs(UUID itemId) implements Condition {}

        record And(List<Condition> conditions) implements Condition {
            public And { conditions = List.copyOf(conditions); }
        }

        record Or(List<Condition> conditions) implements Condition {
            public Or { conditions = List.copyOf(conditions); }
        }

        record Not(Condition condition) implements Condition {}

        /** The item has a stored value for this (non-binary) property. */
        record PropertyExists(UUID propertyId) implements Condition {}

        /** The property's stored value, as text, compared with {@code value}. */
        record PropertyCompare(UUID propertyId, Comparison comparison, String value) implements Condition {}

        /** The item is currently in this state of this state machine. */
        record InState(UUID stateMachineId, UUID stateId) implements Condition {}
    }

    public enum Comparison { EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL, LIKE_IGNORING_CASE }

    /** Rows in order of {@code key}, nulls last, ties broken by item id. */
    public record Sort(SortKey key, boolean descending) {
        public static final Sort DEFAULT = new Sort(SortKey.Column.ITEM_ID, false);
    }

    public sealed interface SortKey {
        enum Column implements SortKey { ITEM_ID, CREATED_DATE, CREATED_BY, UPDATED_DATE, UPDATED_BY, VISIBILITY_STATE }

        /** By item type name; the names are supplied, since the register doesn't hold them. */
        record ItemTypeName(Map<UUID, String> namesByItemTypeId) implements SortKey {
            public ItemTypeName { namesByItemTypeId = Map.copyOf(namesByItemTypeId); }
        }

        /** By a stored (non-binary) property value, compared as {@code type}. */
        record Property(UUID propertyId, ValueType type) implements SortKey {}

        /** By a field of the binary attached under {@code propertyId}: SQL from Binary Store, keyed on the binary's id. */
        record BinaryField(UUID propertyId, CorrelatedSql fieldTextOfBinary, ValueType type) implements SortKey {}
    }

    /** How a stored value compares: as text, or cast to one of these. */
    public enum ValueType { TEXT, INTEGER, DECIMAL, DATE, DATETIME, BOOLEAN }

    public sealed interface Access {
        /** No access condition: an admin, or a caller whose access was checked some other way. */
        enum Unrestricted implements Access { INSTANCE }

        /** Only items carrying at least one of these markers. None means nothing is visible. */
        record AnyMarker(Set<UUID> markerIds) implements Access {
            public AnyMarker { markerIds = Set.copyOf(markerIds); }
        }
    }
}
