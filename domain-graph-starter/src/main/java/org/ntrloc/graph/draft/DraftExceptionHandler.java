package org.ntrloc.graph.draft;

import org.ntrloc.graph.draft.DraftService.DraftAccessDeniedException;
import org.ntrloc.graph.draft.DraftService.DraftConflictException;
import org.ntrloc.graph.draft.DraftService.DraftNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

// The draft registry's refusals, for every controller that works with drafts.
@RestControllerAdvice
public class DraftExceptionHandler {

    @ExceptionHandler(DraftNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(DraftNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(DraftConflictException.class)
    ResponseEntity<Map<String, String>> conflict(DraftConflictException e) {
        return error(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(DraftAccessDeniedException.class)
    ResponseEntity<Map<String, String>> forbidden(DraftAccessDeniedException e) {
        return error(HttpStatus.FORBIDDEN, e);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(Map.of("message", e.getMessage()));
    }
}
