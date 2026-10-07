package org.ntrloc.graph.access;

import java.util.UUID;

// Step into a property's own value as a context -- recursively steppable the same way item/link
// steps are, for nested object-typed properties (e.g. File.content -> metadata -> exif -> colorspace).
// `each` is speculative for now (object-typed properties aren't multi-valued in the schema today) but
// included for forward compatibility, same shape as the item/link steps.
public record StepToProperty(UUID propertyId, boolean each, Context next) implements Context {
}
