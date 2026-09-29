package org.ntrloc.graph.db.partition.schema.definition.view.admin;

import org.ntrloc.graph.db.partition.schema.definition.PropertyType;

// One entry in an AdminBinaryPropertyDefinitionView's dynamicFields -- a dot-path currently observed
// somewhere under this property's binaries' metadata (e.g. "extracted.exif.colorspace"), with the
// type BinaryDynamicPropertyRegistrar inferred for it. See
// docs/ntrloc-dynamic-properties-design-notes.md section 10.
public record DynamicFieldView(String path, PropertyType type) {
}
