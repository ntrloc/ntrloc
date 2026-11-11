package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.principal.ExternalPrincipalResolver;

import org.ntrloc.graph.principal.NtrlocPrincipal;

import org.ntrloc.graph.db.partition.authorization.DefaultUserGroupInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ntrloc.graph.db.partition.changelog.ChangeLog;
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

    private final UserService userService;
    private final DefaultUserGroupInitializer defaultUserGroupInitializer;
    private final ExternalPrincipalResolver principals;
    private final TransactionTemplate transactionTemplate;
    private final SecurityProperties securityProperties;

    public ExternalUserProvisioner(UserService userService, DefaultUserGroupInitializer defaultUserGroupInitializer,
                                   ExternalPrincipalResolver principals, TransactionTemplate transactionTemplate,
                                   SecurityProperties securityProperties) {
        this.userService = userService;
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
            ChangeLog.withSource("sign-in provisioning", () -> transactionTemplate.executeWithoutResult(status -> {
                var user = userService.createUser(displayName, email, false);
                defaultUserGroupInitializer.addUserToDefaultUserGroup(user.id());
            }));
        } catch (DuplicateKeyException _) {
            // A concurrent first login for the same email created the account first; use that one.
        }
        return principals.byEmail(email).orElseThrow();
    }
}
