package org.ntrloc.graph;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one Postgres container for the whole test run, and the databases in it.
 *
 * The shared Spring context (AbstractIntegrationTest) uses the container's own database for the
 * whole run. A test class needing a Spring context of its own -- different settings, so a context
 * no other class reuses -- needs a database no other context touches, since the app keeps state
 * there (accounts, sign-in providers, Flowable's tables) and the shared context stays alive
 * alongside it. Such a class gets a fresh, empty database here instead of a container of its own.
 *
 * The container is started in a static initializer, not by @Testcontainers: see
 * AbstractIntegrationTest for why. It's torn down at JVM exit.
 */
public final class TestDatabases {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final AtomicInteger COUNT = new AtomicInteger();
    // Databases made so far and not yet dropped.
    private static final List<String> CREATED = new ArrayList<>();

    static {
        POSTGRES.start();
    }

    private TestDatabases() {
    }

    /** Points a context at the container's own database: the shared context's. */
    public static void registerShared(DynamicPropertyRegistry registry) {
        register(registry, POSTGRES::getJdbcUrl);
    }

    /**
     * Points a context at a fresh database of its own, made when the context starts. `name` only
     * makes it recognizable.
     */
    public static void registerFresh(DynamicPropertyRegistry registry, String name) {
        register(registry, memoized(() -> create(name)));
    }

    /**
     * A fresh, empty database, by its JDBC URL -- for an application started by hand (see
     * ClusterIntegrationTest). Log in with username() and password().
     */
    public static synchronized String create(String name) {
        dropUnused();
        String database = "t_" + name.toLowerCase().replaceAll("[^a-z0-9]", "_") + "_" + COUNT.incrementAndGet();
        execute("CREATE DATABASE " + database);
        CREATED.add(database);
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    public static String username() {
        return POSTGRES.getUsername();
    }

    public static String password() {
        return POSTGRES.getPassword();
    }

    // A context's database is no use once the context is closed. One still connected to can't be
    // dropped yet -- it's tried again next time.
    private static void dropUnused() {
        CREATED.removeIf(database -> {
            try {
                execute("DROP DATABASE " + database);
                return true;
            } catch (IllegalStateException stillInUse) {
                return false;
            }
        });
    }

    private static void register(DynamicPropertyRegistry registry, Supplier<Object> url) {
        registry.add("spring.datasource.url", url);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // Made once, however often the property is read.
    private static Supplier<Object> memoized(Supplier<String> supplier) {
        return new Supplier<>() {
            private String value;

            @Override
            public synchronized Object get() {
                if (value == null) value = supplier.get();
                return value;
            }
        };
    }

    // On the container's own database, outside a transaction, as CREATE/DROP DATABASE need.
    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.createStatement().execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(sql + ": " + e.getMessage(), e);
        }
    }
}
