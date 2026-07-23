package org.ntrloc.graph.db.partition.process;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

// Same seed-local-accounts flag as LocalAccountSeeder -- this is dev/test seed data in the same
// spirit, and depends on those exact seeded accounts (admin/localuser) existing.
@Component
@ConditionalOnProperty(prefix = "ntrloc.security", name = "seed-local-accounts", havingValue = "true")
@DependsOn({"processGroupInitializer", "localAccountSeeder"})
public class ProcessGroupSeeder {

    private final ProcessGroupRepository groupRepo;
    private final SecurityRepository userRepo;

    public ProcessGroupSeeder(ProcessGroupRepository groupRepo, SecurityRepository userRepo) {
        this.groupRepo = groupRepo;
        this.userRepo = userRepo;
    }

    @PostConstruct
    void init() {
        var reviewers = groupRepo.createGroup("reviewers");
        userRepo.findUserByExternalId("localuser")
                .ifPresent(user -> groupRepo.addUserToGroup(user.id(), reviewers.id()));
    }
}
