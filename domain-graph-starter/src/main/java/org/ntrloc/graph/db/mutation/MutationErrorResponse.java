package org.ntrloc.graph.db.mutation;

import java.util.List;

public record MutationErrorResponse(List<ValidationError> errors) {
}
