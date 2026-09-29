package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.view.DefinedInView;

import java.util.List;
import java.util.UUID;

// A BINARY property -- see AdminPropertyDefinitionView's own comment on the sealed split. No `type`
// field: it's always BINARY, and making that a constructor argument would let a caller construct one
// with the wrong type where a plain field couldn't stop them (the same "make the wrong thing
// structurally unrepresentable" reasoning design-notes section 4 already used for property groups).
// dynamicFields is populated from the binary dynamic-property registry (see
// docs/ntrloc-dynamic-properties-design-notes.md section 10) -- every path currently observed
// somewhere under this property's binaries' metadata, with reference_count > 0. It reflects the
// registry as of the last schema (re)build, not live -- see SchemaManager's own comment on how/when
// the cache refreshes.
public record AdminBinaryPropertyDefinitionView(
        UUID id, String name, String description, PropertyCardinality cardinality,
        PropertyUsage usage, DefinedInView definedIn, UUID controlledListId, boolean facetable,
        List<DynamicFieldView> dynamicFields
) implements AdminPropertyDefinitionView {

    // Jackson's record support only serializes canonical component accessors by default -- since
    // "type" isn't a component here (see the class comment on why), this override was silently
    // dropped from the JSON entirely, with nothing to signal the gap: every consumer that branches on
    // a property's type (e.g. ntrloc-dam's own filePropertyPaths, looking for the BINARY leaf to
    // attach an uploaded file to) saw `type: undefined` and silently failed to find it. @JsonProperty
    // forces it back in despite not being a real component.
    @Override
    @JsonProperty("type")
    public PropertyType type() {
        return PropertyType.BINARY;
    }

    @Override
    public AdminBinaryPropertyDefinitionView withDefinedIn(DefinedInView definedIn) {
        return new AdminBinaryPropertyDefinitionView(id, name, description, cardinality, usage, definedIn, controlledListId, facetable, dynamicFields);
    }
}
