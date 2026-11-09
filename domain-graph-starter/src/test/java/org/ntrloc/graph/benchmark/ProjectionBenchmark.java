package org.ntrloc.graph.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.TestApplication;
import org.ntrloc.graph.TestDatabases;
import org.ntrloc.graph.benchmark.BenchmarkResults.Timing;
import org.ntrloc.graph.benchmark.ProjectionBenchmarkDataset.Seeded;
import org.ntrloc.graph.benchmark.ProjectionBenchmarkDataset.Size;
import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ntrloc.graph.benchmark.ProjectionBenchmarkDataset.ASSET;
import static org.ntrloc.graph.benchmark.ProjectionBenchmarkDataset.PRODUCT;
import static org.ntrloc.graph.benchmark.ProjectionBenchmarkDataset.SUPPLIERS;

// Live projection performance benchmark. Not a test: the name doesn't end in "Test", so ordinary
// builds never run it. Run it on its own with the `benchmarks` profile:
//
//   mvn -pl domain-graph-starter test -Dbenchmarks -DskipUiTests -Dfrontend.skip=true
//
// It seeds a dataset through the HTTP APIs (ProjectionBenchmarkDataset), times each projection
// scenario as an admin and as a non-admin who can see a quarter of the data, saves the results
// (BenchmarkResults) and compares them with an earlier run. Options, as -D properties:
//
//   benchmarks.products / .parts / .suppliers   dataset size (default 10000 / 5000 / 100)
//   benchmarks.warmup / .iterations             requests per scenario (default 10 / 40)
//   benchmarks.label                            name this run, e.g. "baseline"
//   benchmarks.compareTo                        "previous" (default), a label, or a file name
//   benchmarks.threshold                        regression threshold on the median, % (default 15)
//   benchmarks.failOnRegression                 fail the run on a regression (default false)
//   benchmarks.dir                              results directory (the profile sets it)
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectionBenchmark {

    @DynamicPropertySource
    static void registerDatabase(DynamicPropertyRegistry registry) {
        TestDatabases.registerFresh(registry, "projection_benchmark");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper json;

    @Autowired
    private UserStore userStore;

    @Autowired
    private PersonalAccessTokenService tokens;

    private record Scenario(String name, String path, Map<String, Object> body, ToLongFunction<JsonNode> resultCount) {}

    @Test
    void projectionPerformance() {
        var http = new BenchmarkHttp("http://localhost:" + port, json);
        String adminToken = tokens.issueForUser(
                userStore.findUserByEmail("root@example.com").orElseThrow().id(), "projection-benchmark", null).rawToken();

        Size size = new Size(intProperty("benchmarks.products", 10_000), intProperty("benchmarks.parts", 5_000),
                intProperty("benchmarks.suppliers", 100));
        System.out.printf("%nSeeding %d products, %d parts, %d suppliers...%n", size.products(), size.parts(), size.suppliers());
        Seeded seeded = new ProjectionBenchmarkDataset(http, adminToken).seed(size);
        System.out.printf("Seeded in %.1f s%n", seeded.seedMillis() / 1000.0);

        int warmup = intProperty("benchmarks.warmup", 10);
        int iterations = intProperty("benchmarks.iterations", 40);
        Map<String, String> principals = new LinkedHashMap<>();
        principals.put("admin", adminToken);
        principals.put("user", seeded.userToken());

        List<Timing> timings = new ArrayList<>();
        for (Scenario scenario : scenarios(seeded)) {
            for (var principal : principals.entrySet()) {
                timings.add(time(http, scenario, principal.getKey(), principal.getValue(), warmup, iterations));
            }
        }
        checkCounts(timings, seeded);
        System.out.print(BenchmarkResults.table(timings));

        var results = new BenchmarkResults(Path.of(System.getProperty("benchmarks.dir", "../benchmark-results/projection")), json);
        Path saved = results.save(System.getProperty("benchmarks.label", ""), runInfo(seeded, warmup, iterations), timings);
        System.out.println("Saved " + saved.toAbsolutePath().normalize());

        double threshold = Double.parseDouble(System.getProperty("benchmarks.threshold", "15"));
        results.find(System.getProperty("benchmarks.compareTo", "previous"), saved).ifPresentOrElse(baseline -> {
            List<String> regressions = results.compare(baseline, timings, threshold);
            if (Boolean.parseBoolean(System.getProperty("benchmarks.failOnRegression", "false"))) {
                assertThat(regressions).as("projection regressions").isEmpty();
            }
        }, () -> System.out.println("No earlier run to compare with."));
    }

    private List<Scenario> scenarios(Seeded seeded) {
        ToLongFunction<JsonNode> total = r -> r.get("totalCount").asLong();
        Map<String, Object> page = Map.of("itemTypeName", PRODUCT, "sortField", "name", "sortDirection", "ASC", "limit", 50);
        return List.of(
                new Scenario("products: first page", "/api/projection/collection", page, total),
                new Scenario("products: deep page", "/api/projection/collection",
                        with(page, "offset", (int) seeded.visibleProducts() / 2), total),
                new Scenario("products: filtered, sorted", "/api/projection/collection", Map.of(
                        "itemTypeName", PRODUCT, "sortField", "price", "sortDirection", "DESC", "limit", 50,
                        "filter", Map.of("type", "AND", "predicates", List.of(
                                Map.of("type", "PROPERTY_VALUE", "propertyName", "quantity", "operator", "GREATER_THAN_OR_EQUAL", "value", "500"),
                                Map.of("type", "PROPERTY_VALUE", "propertyName", "price", "operator", "LESS_THAN", "value", "250")))), total),
                new Scenario("products: facets", "/api/projection/collection",
                        with(page, "facets", List.of("region", "price", "quantity")), total),
                new Scenario("products: with links", "/api/projection/collection",
                        with(page, "links", Map.of(SUPPLIERS, Map.of())), total),
                new Scenario("products: with permissions", "/api/projection/collection",
                        with(page, "includePermissions", true), total),
                new Scenario("assets: cross-type", "/api/projection/collection",
                        Map.of("itemTypeName", ASSET, "sortField", "name", "sortDirection", "ASC", "limit", 50), total),
                new Scenario("product: item with links", "/api/projection/item", Map.of(
                        "itemTypeName", PRODUCT, "itemId", seeded.visibleProductId(), "links", Map.of(SUPPLIERS, Map.of())),
                        r -> r.has("itemId") ? 1 : 0));
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private static Timing time(BenchmarkHttp http, Scenario scenario, String principal, String token, int warmup, int iterations) {
        long resultCount = -1;
        for (int i = 0; i < warmup; i++) {
            http.send(token, "POST", scenario.path(), scenario.body());
        }
        long[] nanos = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long started = System.nanoTime();
            String body = http.send(token, "POST", scenario.path(), scenario.body());
            nanos[i] = System.nanoTime() - started;
            long count = scenario.resultCount().applyAsLong(http.parse(body));
            if (resultCount >= 0 && count != resultCount) {
                throw new IllegalStateException(scenario.name() + " returned " + count + " results, then " + resultCount);
            }
            resultCount = count;
        }
        return Timing.of(scenario.name(), principal, resultCount, nanos);
    }

    // A fast answer only counts if it's the right one: the counts prove permission filtering held.
    private static void checkCounts(List<Timing> timings, Seeded seeded) {
        for (Timing t : timings) {
            boolean admin = t.principal().equals("admin");
            long expected = switch (t.scenario()) {
                case "products: filtered, sorted" -> -1;
                case "assets: cross-type" -> admin ? seeded.size().products() + seeded.size().parts() : seeded.visibleAssets();
                case "product: item with links" -> 1;
                default -> admin ? seeded.size().products() : seeded.visibleProducts();
            };
            if (expected >= 0) {
                assertThat(t.resultCount()).as(t.key()).isEqualTo(expected);
            } else {
                assertThat(t.resultCount()).as(t.key()).isPositive();
            }
        }
    }

    private static Map<String, Object> runInfo(Seeded seeded, int warmup, int iterations) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("startedAt", OffsetDateTime.now().toString());
        run.put("commit", git("rev-parse", "--short", "HEAD"));
        run.put("uncommittedChanges", !git("status", "--porcelain").isBlank());
        run.put("java", Runtime.version().toString());
        run.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        run.put("processors", Runtime.getRuntime().availableProcessors());
        run.put("dataset", seeded.size());
        run.put("seedSeconds", seeded.seedMillis() / 1000.0);
        run.put("warmup", warmup);
        run.put("iterations", iterations);
        return run;
    }

    private static String git(String... args) {
        try {
            var command = new ArrayList<String>();
            command.add("git");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 ? out : "unknown";
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }

    private static int intProperty(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(name, String.valueOf(defaultValue)));
    }
}
