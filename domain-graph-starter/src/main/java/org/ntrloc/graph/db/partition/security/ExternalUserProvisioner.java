package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.authorization.DefaultUserGroupInitializer;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

// Creates the ntrloc account for someone an external identity provider has authenticated but who
// has never logged in before, if graph.security.auto-provision.enabled is on. The account has no
// local credentials (the person can only log in through that provider until an admin gives it
// some), joins the default user group, and is never an admin -- admin is only ever granted from
// within ntrloc, by another admin. Its profile is set once, here; later logins don't re-sync it.
@Component
public class ExternalUserProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalUserProvisioner.class);

    private final SecurityRepository repo;
    private final DefaultUserGroupInitializer defaultUserGroupInitializer;
    private final ExternalPrincipalResolver principals;
    private final TransactionTemplate transactionTemplate;
    private final SecurityProperties securityProperties;

    public ExternalUserProvisioner(SecurityRepository repo, DefaultUserGroupInitializer defaultUserGroupInitializer,
                                   ExternalPrincipalResolver principals, TransactionTemplate transactionTemplate,
                                   SecurityProperties securityProperties) {
        this.repo = repo;
        this.defaultUserGroupInitializer = defaultUserGroupInitializer;
        this.principals = principals;
        this.transactionTemplate = transactionTemplate;
        this.securityProperties = securityProperties;
    }

    /** The new account's principal, or empty if auto-provisioning is off. */
    public Optional<NtrlocPrincipal> provisionIfEnabled(String email, String displayName) {
        if (!securityProperties.getAutoProvision().isEnabled()) {
            return Optional.empty();
        }
        return Optional.of(provision(email, displayName));
    }

    private NtrlocPrincipal provision(String email, String displayName) {
        LOG.info("Provisioning an ntrloc account for {}", email);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                var user = repo.createUser(displayName, email, false);
                defaultUserGroupInitializer.addUserToDefaultUserGroup(user.id());
            });
        } catch (DuplicateKeyException e) {
            // A concurrent first login for the same email created the account first; use that one.
        }
        return principals.byEmail(email).orElseThrow();
    }
}
