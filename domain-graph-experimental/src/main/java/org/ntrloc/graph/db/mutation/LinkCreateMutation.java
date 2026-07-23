package org.ntrloc.graph.db.mutation;

import org.springframework.lang.Nullable;

import java.util.Map;

// No link type field: link types have no name of their own in this schema, and the concrete
// link type is entirely derivable from firstItem/secondItem's resolved perspectives (see
// MutationRequestProcessor). Exactly two endpoints -- a List would let an invalid arity compile.
public record LinkCreateMutation(LinkEndpointReference firstItem, LinkEndpointReference secondItem,
                                  @Nullable Map<String, Object> properties) implements LinkMutation {
}
