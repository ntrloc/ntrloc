package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.List;
import java.util.UUID;

// A property group is purely structural: it names a place in the tree (paths, name scoping) and
// holds properties and further groups. It has no value and is never grantable. Properties and
// groups are separate lists, not one mixed list, so the difference between the two kinds is carried
// by the types rather than by an instanceof check in every consumer.
//
// traitNamespace marks the synthetic group a trait's contributions are projected under (its name is
// the trait's name, its id the trait's id) -- not a stored group row. Consumers walk it like any
// other group; only the schema editor needs to tell the two apart.
public record AdminPropertyGroupView(
        UUID id, String name, String description, DefinedInView definedIn, boolean traitNamespace,
        List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups
) {

    public AdminPropertyGroupView withDefinedIn(DefinedInView definedIn) {
        return new AdminPropertyGroupView(id, name, description, definedIn, traitNamespace, properties, groups);
    }

    public AdminPropertyGroupView withChildren(List<AdminPropertyDefinitionView> properties, List<AdminPropertyGroupView> groups) {
        return new AdminPropertyGroupView(id, name, description, definedIn, traitNamespace, properties, groups);
    }
}
