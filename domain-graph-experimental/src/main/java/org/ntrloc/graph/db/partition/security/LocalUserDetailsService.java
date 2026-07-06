package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class LocalUserDetailsService implements ReactiveUserDetailsService {

    private final SecurityRepository repo;

    public LocalUserDetailsService(SecurityRepository repo) {
        this.repo = repo;
    }

    @Override
    public Mono<UserDetails> findByUsername(String username) {
        return Mono.fromCallable(() -> repo.findCredentialsByEmail(username))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(opt -> opt
                        .map(c -> Mono.just((UserDetails) User.builder()
                                .username(c.email())
                                .password(c.passwordHash())
                                .roles(c.role())
                                .disabled(!c.active())
                                .build()))
                        .orElse(Mono.empty()));
    }
}
