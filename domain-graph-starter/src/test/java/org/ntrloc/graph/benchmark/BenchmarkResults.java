package org.ntrloc.graph.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// One benchmark run's results: saved as JSON under the results directory (outside target/, so
// `mvn clean` keeps them), and compared against an earlier run.
//
// Timings depend on the machine, so compare runs from the same one. A run's median is what's
// compared; one slower than the threshold counts as a regression.
final class BenchmarkResults {

    record Timing(String scenario, String principal, int iterations, long resultCount,
                  double medianMs, double p90Ms, double meanMs, double minMs, double maxMs) {

        static Timing of(String scenario, String principal, long resultCount, long[] nanos) {
            long[] sorted = nanos.clone();
            Arrays.sort(sorted);
            double mean = Arrays.stream(sorted).average().orElse(0);
            return new Timing(scenario, principal, sorted.length, resultCount,
                    ms(percentile(sorted, 50)), ms(percentile(sorted, 90)), ms(mean), ms(sorted[0]), ms(sorted[sorted.length - 1]));
        }

        String key() {
            return scenario + " / " + principal;
        }

        private static double percentile(long[] sorted, int p) {
            int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            return sorted[Math.clamp(index, 0, sorted.length - 1)];
        }

        private static double ms(double nanos) {
            return Math.round(nanos / 10_000.0) / 100.0;
        }
    }

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;
    private final JsonMapper json;

    BenchmarkResults(Path directory, JsonMapper json) {
        this.directory = directory;
        this.json = json;
    }

    Path save(String label, Map<String, Object> run, List<Timing> timings) {
        try {
            Files.createDirectories(directory);
            String name = FILE_TIME.format(OffsetDateTime.now(ZoneOffset.UTC)) + (label.isBlank() ? "" : "-" + label) + ".json";
            Map<String, Object> document = new LinkedHashMap<>(run);
            document.put("label", label);
            document.put("timings", timings);
            Path file = directory.resolve(name);
            Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(document));
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // `compareTo` is "previous" (the latest run before this one), a label (the latest run with it),
    // or a file name in the results directory.
    Optional<Path> find(String compareTo, Path current) {
        List<Path> earlier = runs().stream().filter(p -> !p.equals(current)).toList();
        if (compareTo.equals("previous")) {
            return earlier.isEmpty() ? Optional.empty() : Optional.of(earlier.getLast());
        }
        Path named = directory.resolve(compareTo);
        if (Files.isRegularFile(named)) {
            return Optional.of(named);
        }
        return earlier.reversed().stream().filter(p -> compareTo.equals(read(p).path("label").asString())).findFirst();
    }

    // Prints the comparison and returns the regressions (scenarios whose median rose by more than
    // thresholdPercent).
    List<String> compare(Path baseline, List<Timing> timings, double thresholdPercent) {
        Map<String, Double> before = new LinkedHashMap<>();
        for (JsonNode t : read(baseline).path("timings")) {
            before.put(t.get("scenario").asString() + " / " + t.get("principal").asString(), t.get("medianMs").asDouble());
        }
        List<String> regressions = new ArrayList<>();
        StringBuilder table = new StringBuilder("%nCompared with %s (median ms; regression above +%.0f%%)%n".formatted(baseline.getFileName(), thresholdPercent));
        table.append("%-36s %10s %10s %9s%n".formatted("scenario / principal", "before", "now", "change"));
        for (Timing t : timings) {
            Double was = before.get(t.key());
            if (was == null) {
                table.append("%-36s %10s %10.2f %9s%n".formatted(t.key(), "-", t.medianMs(), "new"));
                continue;
            }
            double change = was == 0 ? 0 : (t.medianMs() - was) / was * 100;
            boolean regressed = change > thresholdPercent;
            if (regressed) {
                regressions.add("%s: %.2f -> %.2f ms (%+.1f%%)".formatted(t.key(), was, t.medianMs(), change));
            }
            table.append("%-36s %10.2f %10.2f %+8.1f%%%s%n".formatted(t.key(), was, t.medianMs(), change, regressed ? "  REGRESSION" : ""));
        }
        System.out.print(table);
        return regressions;
    }

    static String table(List<Timing> timings) {
        StringBuilder table = new StringBuilder("%n%-36s %8s %8s %8s %8s %8s %8s%n".formatted(
                "scenario / principal", "results", "median", "p90", "mean", "min", "max"));
        for (Timing t : timings) {
            table.append("%-36s %8d %8.2f %8.2f %8.2f %8.2f %8.2f%n".formatted(
                    t.key(), t.resultCount(), t.medianMs(), t.p90Ms(), t.meanMs(), t.minMs(), t.maxMs()));
        }
        return table.toString();
    }

    private List<Path> runs() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JsonNode read(Path file) {
        try {
            return json.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
