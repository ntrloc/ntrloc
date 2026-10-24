package org.ntrloc.graph.mutation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyGroupView;

// The names a payload can address at one level: leaf properties and property groups, which share
// one namespace (sibling-name uniqueness is enforced in the schema). Trait contributions appear
// as groups named for the trait, so a payload addresses them as {"File": {"name": ...}}.
record PropertyScope(Map<String, AdminPropertyDefinitionView> properties, Map<String, AdminPropertyGroupView> groups) {

    static PropertyScope of(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups) {
        return new PropertyScope(
                properties.stream().collect(Collectors.toMap(AdminPropertyDefinitionView::name, p -> p, (a, b) -> a)),
                groups.stream().collect(Collectors.toMap(AdminPropertyGroupView::name, g -> g, (a, b) -> a)));
    }
}
