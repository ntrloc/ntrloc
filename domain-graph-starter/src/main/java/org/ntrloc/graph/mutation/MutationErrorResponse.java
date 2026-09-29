package org.ntrloc.graph.mutation;

import java.util.List;

public record MutationErrorResponse(List<ValidationError> errors) {
}
