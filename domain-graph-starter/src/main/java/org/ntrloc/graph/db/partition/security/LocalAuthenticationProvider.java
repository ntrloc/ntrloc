package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

// Local (email + password) login. Authenticates with the user's NtrlocPrincipal itself as the
// Authentication's principal, like a PAT does. Every failure -- unknown email, no local
// credentials, inactive account, wrong password -- is the same BadCredentialsException, and an
// unknown email still pays for one hash comparison, so neither the message nor the timing reveals
// which emails have accounts.
@Component
public class LocalAuthenticationProvider implements AuthenticationProvider {

    private final ExternalPrincipalResolver principals;
    private final SecurityRepository repo;
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final String unknownUserHash = passwordEncoder.encode("unknown-user");

    public LocalAuthenticationProvider(ExternalPrincipalResolver principals, SecurityRepository repo) {
        this.principals = principals;
        this.repo = repo;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String email = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());

        Optional<NtrlocPrincipal> principal = principals.byEmail(email);
        Optional<SecurityRepository.LocalCredentialsRow> credentials =
                principal.flatMap(p -> repo.findCredentialsByUserId(p.id()));
        boolean passwordMatches = passwordEncoder.matches(password,
                credentials.map(SecurityRepository.LocalCredentialsRow::passwordHash).orElse(unknownUserHash));

        if (credentials.isEmpty() || !credentials.get().active() || !passwordMatches) {
            throw new BadCredentialsException("Bad credentials");
        }
        return UsernamePasswordAuthenticationToken.authenticated(principal.get(), null, List.of());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
