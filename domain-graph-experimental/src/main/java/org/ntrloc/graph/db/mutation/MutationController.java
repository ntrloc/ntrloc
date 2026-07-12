package org.ntrloc.graph.db.mutation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mutation")
public class MutationController {

    private final MutationRequestProcessor processor;

    public MutationController(MutationRequestProcessor processor) {
        this.processor = processor;
    }

    @PostMapping
    ResponseEntity<MutationResponse> mutate(@RequestBody MutationRequest request) {
        return ResponseEntity.ok(processor.process(request));
    }
}
