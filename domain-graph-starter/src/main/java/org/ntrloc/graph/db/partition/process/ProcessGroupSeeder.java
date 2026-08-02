package org.ntrloc.graph.db.partition.process;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "graph.security", name = "seed-local-accounts", havingValue = "true")
@DependsOn({"processGroupInitializer", "localAccountSeeder"})
public class ProcessGroupSeeder {

    private final ProcessGroupRepository groupRepo;
    private final SecurityRepository userRepo;
    private final JdbcClient jdbcClient;

    public ProcessGroupSeeder(ProcessGroupRepository groupRepo, SecurityRepository userRepo, JdbcClient jdbcClient) {
        this.groupRepo = groupRepo;
        this.userRepo = userRepo;
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        boolean exists = jdbcClient.sql("SELECT COUNT(*) FROM process_group WHERE name = :name")
                .param("name", "reviewers")
                .query(Integer.class).single() > 0;
        if (exists) return;

        var reviewers = groupRepo.createGroup("reviewers");
        userRepo.findUserByExternalId("localuser")
                .ifPresent(user -> groupRepo.addUserToGroup(user.id(), reviewers.id()));
    }
}
