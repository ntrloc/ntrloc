package org.ntrloc.graph.db.partition.changelog;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.UserService;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Browsers hear when access changes in live (docs/ntrloc-system-model-notes.md §13.6), over the existing
// event stream: an admin's tab, once the change commits; a non-admin's never; and nothing for a change that
// rolls back.
class AccessChangedEventIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserService userService;

    @Autowired
    private UserStore userStore;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // Listens on the event stream as `email`; completes once an accessChanged event arrives.
    private CompletableFuture<Boolean> listen(String email) throws Exception {
        HttpResponse<Stream<String>> events = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events?types=accessChanged"))
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(email)).GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(events.statusCode()).isEqualTo(200);
        Stream<String> lines = events.body();
        return CompletableFuture.supplyAsync(() -> lines.anyMatch(line -> line.replace(" ", "").equals("event:accessChanged")));
    }

    private UUID root() {
        return userStore.findUserGroupByName("everyone").orElseThrow().id();
    }

    @Test
    void anAdminsTab_hearsWhenAGroupChanges() throws Exception {
        var heard = listen("root@example.com");

        userService.createUserGroup("pushed-" + UUID.randomUUID(), root());

        assertThat(heard.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aNonAdminsTab_doesNot() throws Exception {
        var heard = listen("alice@example.com");

        userService.createUserGroup("not-for-alice-" + UUID.randomUUID(), root());

        assertThatThrownBy(() -> heard.get(3, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
    }

    @Test
    void aChangeThatRollsBack_isNotAnnounced() throws Exception {
        var heard = listen("root@example.com");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(_ -> {
            userService.createUserGroup("rolled-back-" + UUID.randomUUID(), root());
            throw new IllegalStateException("roll back");
        })).hasMessage("roll back");

        assertThatThrownBy(() -> heard.get(3, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
    }
}
