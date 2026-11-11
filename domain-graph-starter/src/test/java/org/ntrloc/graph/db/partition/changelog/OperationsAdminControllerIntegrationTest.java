package org.ntrloc.graph.db.partition.changelog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.UserService;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The Operations screen's changelog retention (docs/ntrloc-drafts-plan.md step 2.2): read and set by
// admins, honoured at once when saved.
class OperationsAdminControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN = "root@example.com";
    private static final String URI = "/api/admin/operations/changelog";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ChangeLogRetention retention;

    @Autowired
    private UserService userService;

    @Autowired
    private UserStore userStore;

    @Autowired
    private JdbcClient jdbcClient;

    @AfterEach
    void keepEverything() {
        retention.setRetentionDays(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get() {
        return webTestClient.get().uri(URI).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(ADMIN))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec put(Integer days, String as) {
        var body = new HashMap<String, Object>();
        body.put("retentionDays", days);
        return webTestClient.put().uri(URI).header(HttpHeaders.AUTHORIZATION, bearerTokenFor(as)).bodyValue(body).exchange();
    }

    @Test
    void byDefault_everythingIsKept_andTheLogsSizeIsShown() {
        assertThat(get()).containsEntry("retentionDays", null).containsKeys("entries", "oldest");
        assertThat(((Number) get().get("entries")).longValue()).isPositive();
    }

    @Test
    void settingRetention_prunesAtOnce() {
        long start = jdbcClient.sql("SELECT COALESCE(MAX(seq), 0) FROM config_change_log").query(Long.class).single();
        userService.createUserGroup("old-" + UUID.randomUUID(), userStore.findUserGroupByName("everyone").orElseThrow().id());
        jdbcClient.sql("UPDATE config_change_log SET at = now() - interval '40 days' WHERE seq > :start").param("start", start).update();

        put(30, ADMIN).expectStatus().isOk().expectBody().jsonPath("$.retentionDays").isEqualTo(30);

        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM config_change_log WHERE seq > :start").param("start", start)
                .query(Long.class).single()).isZero();
    }

    @Test
    void clearingRetention_keepsEverythingAgain() {
        put(30, ADMIN).expectStatus().isOk();

        put(null, ADMIN).expectStatus().isOk().expectBody().jsonPath("$.retentionDays").value(org.hamcrest.Matchers.nullValue());
    }

    @Test
    void lessThanADay_isRefused() {
        put(0, ADMIN).expectStatus().isBadRequest();
    }

    @Test
    void onlyAdmins() {
        webTestClient.get().uri(URI).header(HttpHeaders.AUTHORIZATION, bearerTokenFor("alice@example.com"))
                .exchange().expectStatus().isForbidden();
        put(30, "alice@example.com").expectStatus().isForbidden();
    }
}
