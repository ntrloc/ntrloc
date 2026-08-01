package org.ntrloc.graph.db;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/items")
public class LedgerPropertyHistoryController {

    private final LedgerPropertyHistoryService historyService;

    public LedgerPropertyHistoryController(LedgerPropertyHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/{itemId}/properties/{propertyId}/history")
    ResponseEntity<List<LedgerPropertyHistoryService.PropertyHistoryEntry>> history(
            @PathVariable UUID itemId, @PathVariable UUID propertyId) {
        return ResponseEntity.ok(historyService.history(itemId, propertyId));
    }
}
