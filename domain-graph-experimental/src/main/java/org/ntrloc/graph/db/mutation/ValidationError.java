package org.ntrloc.graph.db.mutation;

// path identifies which part of the request failed, e.g. "items[1].itemTypeName" or
// "links[0].firstItem.perspectiveName".
public record ValidationError(String path, String message) {
}
