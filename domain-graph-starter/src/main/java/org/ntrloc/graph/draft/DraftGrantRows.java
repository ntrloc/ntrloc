package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader.ItemTypeGrantRow;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader.LinkPerspectiveGrantRow;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader.MarkerGrantRow;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader.MarkerScopedGrantRow;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader.PropertyGrantRow;
import org.ntrloc.graph.db.partition.authorization.repository.GrantsView;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

// A draft's grant rows: live's, with each of the draft's overrides applied -- one op on one row, the
// row's other ops left as live has them. With no overrides, exactly live's rows.
final class DraftGrantRows {

    private final List<ItemTypeGrantRow> itemTypeGrants;
    private final List<MarkerGrantRow> markerGrants;
    private final List<PropertyGrantRow> propertyGrants;
    private final List<PropertyGrantRow> linkPropertyGrants;
    private final List<LinkPerspectiveGrantRow> linkPerspectiveGrants;
    private final List<MarkerScopedGrantRow> transitionGrants;
    private final List<MarkerScopedGrantRow> stateMachineStartGrants;

    private DraftGrantRows(GrantsView.Rows live) {
        itemTypeGrants = new ArrayList<>(live.itemTypeGrants());
        markerGrants = new ArrayList<>(live.markerGrants());
        propertyGrants = new ArrayList<>(live.propertyGrants());
        linkPropertyGrants = new ArrayList<>(live.linkPropertyGrants());
        linkPerspectiveGrants = new ArrayList<>(live.linkPerspectiveGrants());
        transitionGrants = new ArrayList<>(live.transitionGrants());
        stateMachineStartGrants = new ArrayList<>(live.stateMachineStartGrants());
    }

    static GrantsView.Rows apply(GrantsView.Rows live, List<DraftGrantOverride> overrides) {
        var rows = new DraftGrantRows(live);
        overrides.forEach(rows::apply);
        return new GrantsView.Rows(List.copyOf(rows.itemTypeGrants), List.copyOf(rows.markerGrants), List.copyOf(rows.propertyGrants),
                List.copyOf(rows.linkPropertyGrants), List.copyOf(rows.linkPerspectiveGrants), List.copyOf(rows.transitionGrants),
                List.copyOf(rows.stateMachineStartGrants));
    }

    private void apply(DraftGrantOverride override) {
        String pt = override.principalType();
        UUID pid = override.principalId();
        Permission p = override.permission();
        boolean value = override.value();
        String op = p.op();
        if (Permission.ITEM_TYPE.equals(p.kind())) {
            var row = new ItemTypeGrantRow(pt, pid, "item-type:" + op, p.itemTypeId());
            setPresent(itemTypeGrants, row, value);
            return;
        }
        UUID markerId = p.markerId();
        UUID targetId = p.targetId();
        switch (p.area()) {
            case Permission.AREA_ITEM -> change(markerGrants,
                    r -> r.principalType().equals(pt) && r.principalId().equals(pid) && r.markerId().equals(markerId),
                    () -> new MarkerGrantRow(pt, pid, markerId, false, false),
                    r -> new MarkerGrantRow(pt, pid, markerId, "read".equals(op) ? value : r.itemCanRead(), "delete".equals(op) ? value : r.itemCanDelete()));
            case Permission.AREA_PROPERTY -> changeProperty(propertyGrants, pt, pid, markerId, targetId, op, value);
            case Permission.AREA_LINK_PROPERTY -> changeProperty(linkPropertyGrants, pt, pid, markerId, targetId, op, value);
            case Permission.AREA_PERSPECTIVE -> change(linkPerspectiveGrants,
                    r -> r.principalType().equals(pt) && r.principalId().equals(pid) && r.markerId().equals(markerId) && r.perspectiveId().equals(targetId),
                    () -> new LinkPerspectiveGrantRow(pt, pid, markerId, targetId, false, false, false),
                    r -> new LinkPerspectiveGrantRow(pt, pid, markerId, targetId, "create".equals(op) ? value : r.canCreate(),
                            "read".equals(op) ? value : r.canRead(), "delete".equals(op) ? value : r.canDelete()));
            case Permission.AREA_TRANSITION -> setPresent(transitionGrants, new MarkerScopedGrantRow(pt, pid, markerId, targetId), value);
            case Permission.AREA_START -> setPresent(stateMachineStartGrants, new MarkerScopedGrantRow(pt, pid, markerId, targetId), value);
            default -> throw new IllegalArgumentException("unknown area " + p.area());
        }
    }

    private static void changeProperty(List<PropertyGrantRow> rows, String pt, UUID pid, UUID markerId, UUID propertyId, String op, boolean value) {
        change(rows,
                r -> r.principalType().equals(pt) && r.principalId().equals(pid) && r.markerId().equals(markerId) && r.propertyId().equals(propertyId),
                () -> new PropertyGrantRow(pt, pid, markerId, propertyId, false, false),
                r -> new PropertyGrantRow(pt, pid, markerId, propertyId, "read".equals(op) ? value : r.canRead(), "write".equals(op) ? value : r.canWrite()));
    }

    // Changes the matching row, or adds a new one (as `blank` changed) when there's none.
    private static <T> void change(List<T> rows, Predicate<T> matches, Supplier<T> blank, UnaryOperator<T> changed) {
        for (int i = 0; i < rows.size(); i++) {
            if (matches.test(rows.get(i))) {
                rows.set(i, changed.apply(rows.get(i)));
                return;
            }
        }
        rows.add(changed.apply(blank.get()));
    }

    private static <T> void setPresent(List<T> rows, T row, boolean present) {
        rows.remove(row);
        if (present) rows.add(row);
    }
}
