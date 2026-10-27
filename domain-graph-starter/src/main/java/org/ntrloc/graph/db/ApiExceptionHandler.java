package org.ntrloc.graph.db;

import org.ntrloc.graph.db.partition.schema.SchemaConflictException;
import org.ntrloc.graph.mutation.MutationErrorResponse;
import org.ntrloc.graph.mutation.MutationValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// App-wide: without this, an IllegalArgumentException thrown by any mutation-validation guard
// (SchemaMutationValidation and friends) reaches the browser as a bare "Request failed: 500" with
// no message -- server.error.include-message defaults to "never" and nothing here previously set
// it or handled the exception, so the message never left the server. IllegalArgumentException is
// this app's existing vocabulary for "the request is invalid, here's why" (not a bug, not a 500),
// so it maps to 400 with the message in the body, matching what every admin-ui fetch() caller
// already expects (see e.g. schema-service.js: `error.message || 'Request failed: ' + status`).
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ErrorResponse(String message) {}

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(SchemaConflictException.class)
    ResponseEntity<ErrorResponse> handleSchemaConflict(SchemaConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(e.getMessage()));
    }

    // Catches MutationValidationException for every caller that doesn't already handle it locally
    // (MutationController does, inline) -- in particular EntityManagerImpl.startStateMachine/
    // executeTransition via ItemController, which have no catch block of their own. Keeps every
    // mutation rejection (validation or permission) on the one 400 + MutationErrorResponse shape,
    // rather than a permission denial alone surfacing as a bare 500.
    @ExceptionHandler(MutationValidationException.class)
    ResponseEntity<MutationErrorResponse> handleMutationValidation(MutationValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new MutationErrorResponse(e.errors()));
    }
}
