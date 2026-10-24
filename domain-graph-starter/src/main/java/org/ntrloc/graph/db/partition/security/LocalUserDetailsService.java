package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LocalUserDetailsService implements UserDetailsService {

    private final SecurityRepository repo;

    public LocalUserDetailsService(SecurityRepository repo) {
        this.repo = repo;
    }

    // Returns an NtrlocUserDetails, not a plain Spring Security User -- see that class's own
    // comment for why: it's what lets Authentication.getPrincipal() already carry the full
    // NtrlocPrincipal shape for a locally-authenticated session, closing the "every controller
    // re-queries SecurityRepository per request" gap PrincipalResolver.resolve() otherwise has.
    // The login name is the user's email (case-insensitive); a user without local credentials
    // (e.g. one who only signs in through LDAP or OAuth) can't log in this way.
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return buildUserDetails(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
    }

    private Optional<UserDetails> buildUserDetails(String username) {
        Optional<SecurityRepository.UserRow> user = repo.findUserByEmail(username);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        Optional<SecurityRepository.LocalCredentialsRow> credentials = repo.findCredentialsByUserId(user.get().id());
        if (credentials.isEmpty()) {
            return Optional.empty();
        }
        NtrlocPrincipal principal = new ResolvedPrincipal(
                user.get().id(), user.get().displayName(), user.get().email(),
                repo.getUserGroupIdsForUser(user.get().id()), user.get().isSuperuser());
        SecurityRepository.LocalCredentialsRow c = credentials.get();
        return Optional.of(new NtrlocUserDetails(principal, c.passwordHash(), c.role(), c.active()));
    }
}
