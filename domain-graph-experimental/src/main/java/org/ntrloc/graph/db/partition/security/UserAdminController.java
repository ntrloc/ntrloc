package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Scoped to what an assignee/candidate picker needs (externalId/displayName) -- not a general
// Users-management CRUD API. The admin UI's "Users" nav tab is a separate, unbuilt feature.
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    public record UserView(String externalId, String displayName) {}

    private final SecurityRepository repo;

    public UserAdminController(SecurityRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    List<UserView> listUsers() {
        return repo.listUsers().stream()
                .map(u -> new UserView(u.externalId(), u.displayName()))
                .toList();
    }
}
