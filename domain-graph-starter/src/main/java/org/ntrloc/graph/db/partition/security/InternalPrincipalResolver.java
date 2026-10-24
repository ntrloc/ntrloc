package org.ntrloc.graph.db.partition.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the principal for a user the system has stored a reference to (security_user.id), when
 * it acts as that user with no one logged in -- e.g. a process's runAsUser, or the user who
 * started a long-running process instance. See {@link ExternalPrincipalResolver} for requests.
 */
public interface InternalPrincipalResolver {

    /**
     * The Flowable process variable a process instance's user is stored under: a bare UUID string
     * (security_user.id), never a serialized principal, so it is re-resolved through
     * {@link #byId} on every read.
     */
    String PRINCIPAL_VARIABLE = "principal";

    /**
     * The user's current principal -- never cached, so their groups and admin status are
     * whatever they are now. Empty if the user has since been deleted, rather than a stale,
     * possibly more-privileged snapshot.
     */
    Optional<NtrlocPrincipal> byId(UUID id);
}
