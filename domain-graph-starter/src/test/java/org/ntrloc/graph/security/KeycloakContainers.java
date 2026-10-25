package org.ntrloc.graph.security;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.Map;

/**
 * The one Keycloak server every OIDC test in this JVM shares, with the "ntrloc" realm imported
 * from oidc/ntrloc-realm.json (Keycloak requires an import file to be named after its realm).
 * Started on first use and left running until the JVM exits, like every singleton container in
 * these tests. Keycloak derives its issuer from the host name each request arrives on, so one
 * server serves tests that reach it at localhost and tests that reach it at
 * host.testcontainers.internal alike; the realm is read-only to all of them.
 */
public final class KeycloakContainers {

    public static final int PORT = 8080;
    public static final String CLIENT_ID = "ntrloc";
    public static final String CLIENT_SECRET = "ntrloc-client-secret";
    public static final String USER_PASSWORD = "oidc-secret";

    private KeycloakContainers() {
    }

    public static GenericContainer<?> shared() {
        return Holder.KEYCLOAK;
    }

    // Started when first referenced (the JVM's lazy class initialization), so a test run that
    // never touches OIDC never starts Keycloak.
    private static final class Holder {
        private static final GenericContainer<?> KEYCLOAK = newKeycloak();

        static {
            KEYCLOAK.start();
        }
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> newKeycloak() {
        return new GenericContainer<>("quay.io/keycloak/keycloak:26.4.2")
                .withCommand("start-dev", "--import-realm")
                .withEnv(Map.of("KC_BOOTSTRAP_ADMIN_USERNAME", "admin", "KC_BOOTSTRAP_ADMIN_PASSWORD", "admin"))
                .withCopyFileToContainer(MountableFile.forClasspathResource("oidc/ntrloc-realm.json"),
                        "/opt/keycloak/data/import/ntrloc-realm.json")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHttp("/realms/ntrloc/.well-known/openid-configuration").forPort(PORT)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /** The realm's issuer URL, as reached from this JVM. */
    public static String issuerUri(GenericContainer<?> keycloak) {
        return "http://localhost:" + keycloak.getMappedPort(PORT) + "/realms/ntrloc";
    }
}
