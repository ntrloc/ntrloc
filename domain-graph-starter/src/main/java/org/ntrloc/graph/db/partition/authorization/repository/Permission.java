package org.ntrloc.graph.db.partition.authorization.repository;

import java.util.Set;
import java.util.UUID;

/**
 * One permission a grant can give, named the way the grants API and draft grant overrides name it
 * (docs/ntrloc-system-model-notes.md §7.2.1):
 * <ul>
 *   <li>{@code itemType}: {@code itemTypeId}, op {@code read | create}</li>
 *   <li>{@code marker}, area {@code item}: op {@code read | delete}</li>
 *   <li>{@code marker}, area {@code property | linkProperty}: {@code targetId} a property, op {@code read | write}</li>
 *   <li>{@code marker}, area {@code perspective}: {@code targetId} a link perspective, op {@code read | create | delete}</li>
 *   <li>{@code marker}, area {@code transition | start}: {@code targetId} a transition or state machine, no op</li>
 * </ul>
 */
public record Permission(String kind, UUID itemTypeId, UUID markerId, String area, UUID targetId, String op) {

    public static final String ITEM_TYPE = "itemType";
    public static final String MARKER = "marker";
    public static final String AREA_ITEM = "item";
    public static final String AREA_PROPERTY = "property";
    public static final String AREA_LINK_PROPERTY = "linkProperty";
    public static final String AREA_PERSPECTIVE = "perspective";
    public static final String AREA_TRANSITION = "transition";
    public static final String AREA_START = "start";

    public Permission {
        if (ITEM_TYPE.equals(kind)) {
            require(itemTypeId != null && markerId == null && area == null && targetId == null, "an item-type permission names only its item type");
            require(Set.of("read", "create").contains(op), "an item-type permission's op is read or create");
        } else if (MARKER.equals(kind)) {
            require(markerId != null && itemTypeId == null, "a marker permission names its marker");
            require(AREA_ITEM.equals(area) ? targetId == null : targetId != null, "only the item area has no target");
            require(opsFor(area).contains(op == null ? "" : op), "op " + op + " doesn't apply to area " + area);
        } else {
            throw new IllegalArgumentException("kind is itemType or marker");
        }
    }

    public static Permission itemType(UUID itemTypeId, String op) {
        return new Permission(ITEM_TYPE, itemTypeId, null, null, null, op);
    }

    public static Permission marker(UUID markerId, String area, UUID targetId, String op) {
        return new Permission(MARKER, null, markerId, area, targetId, op);
    }

    private static Set<String> opsFor(String area) {
        if (area == null) throw new IllegalArgumentException("a marker permission names its area");
        return switch (area) {
            case AREA_ITEM -> Set.of("read", "delete");
            case AREA_PROPERTY, AREA_LINK_PROPERTY -> Set.of("read", "write");
            case AREA_PERSPECTIVE -> Set.of("read", "create", "delete");
            case AREA_TRANSITION, AREA_START -> Set.of("");
            default -> throw new IllegalArgumentException("unknown area " + area);
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
