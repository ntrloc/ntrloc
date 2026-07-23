package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

@Component
public class LocalUserDetailsService implements ReactiveUserDetailsService {

    private final SecurityRepository repo;

    public LocalUserDetailsService(SecurityRepository repo) {
        this.repo = repo;
    }

    // Returns an NtrlocUserDetails, not a plain Spring Security User -- see that class's own
    // comment for why: it's what lets Authentication.getPrincipal() already carry the full
    // NtrlocPrincipal shape for a locally-authenticated session, closing the "every controller
    // re-queries SecurityRepository per request" gap PrincipalResolver.resolve() otherwise has.
    // Both DB reads (credentials, then identity+groupIds) happen inside this one blocking
    // callable rather than as two separate reactive steps -- there's no async boundary between
    // them worth paying for, and email/externalId are the same login identifier here (local
    // credentials are keyed by email; security_user by external_id) so the second lookup only
    // ever runs once the first has already confirmed the account exists.
    @Override
    public Mono<UserDetails> findByUsername(String username) {
        return Mono.fromCallable(() -> buildUserDetails(username))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(opt -> opt.map(Mono::just).orElseGet(Mono::empty));
    }

    private Optional<UserDetails> buildUserDetails(String username) {
        Optional<SecurityRepository.LocalCredentialsRow> credentials = repo.findCredentialsByEmail(username);
        if (credentials.isEmpty()) {
            return Optional.empty();
        }
        Optional<SecurityRepository.UserRow> user = repo.findUserByExternalId(username);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        NtrlocPrincipal principal = new ResolvedPrincipal(
                user.get().id(), user.get().externalId(), user.get().displayName(),
                repo.getGroupIdsForUser(user.get().id()), user.get().isSuperuser());
        SecurityRepository.LocalCredentialsRow c = credentials.get();
        return Optional.of(new NtrlocUserDetails(principal, c.passwordHash(), c.role(), c.active()));
    }
}
