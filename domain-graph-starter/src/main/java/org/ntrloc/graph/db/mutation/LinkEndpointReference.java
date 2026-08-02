package org.ntrloc.graph.db.mutation;

// perspectiveName, not perspectiveId -- clients never see internal schema ids. Link types have
// no name of their own in this schema (only perspectives do), and perspective names aren't even
// unique per item type, so the concrete link type is resolved from both endpoints together, not
// named directly (see MutationRequestProcessor).
public record LinkEndpointReference(String perspectiveName, ItemReference item) {
}
