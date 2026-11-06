package org.ntrloc.graph.db.partition.register;

import org.ntrloc.graph.db.partition.CorrelatedSql;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A projection already resolved against the schema: every name is an id, every permission is a plain
 * marker condition, and every request error has already been raised. RegisterStore compiles it to SQL
 * against its own tables and trusts it as given. See docs/ntrloc-architecture-rationalization.md §5.1.
 *
 * @param itemTypeIds the item types whose rows are read (exactly one, until cross-type queries move here)
 * @param filter      which rows match
 * @param access      which rows the caller may see at all
 */
public record ExplicitProjection(Set<UUID> itemTypeIds, Condition filter, Access access) {

    public ExplicitProjection {
        itemTypeIds = Set.copyOf(itemTypeIds);
        if (itemTypeIds.isEmpty()) throw new IllegalArgumentException("An ExplicitProjection needs at least one item type");
    }

    /** One item, by its business id, with no access condition (the caller has already checked it). */
    public static ExplicitProjection singleItem(UUID itemTypeId, UUID itemId) {
        return new ExplicitProjection(Set.of(itemTypeId), new Condition.ItemIdIs(itemId), Access.Unrestricted.INSTANCE);
    }

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
