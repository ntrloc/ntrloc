package org.ntrloc.graph.db.mutation;

import java.util.List;

// Carries every validation error found across the whole request, not just the first -- a
// request commonly has more than one bad reference, and reporting them one at a time would mean
// a client has to round-trip once per error to find them all.
public class MutationValidationException extends RuntimeException {

    private final transient List<ValidationError> errors;

    public MutationValidationException(List<ValidationError> errors) {
        super("Mutation request failed validation: " + errors.size() + " error(s)");
        this.errors = errors;
    }

    public List<ValidationError> errors() {
        return errors;
    }
}
