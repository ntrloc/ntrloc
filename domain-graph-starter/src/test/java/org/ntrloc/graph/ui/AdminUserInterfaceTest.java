package org.ntrloc.graph.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.ntrloc.graph.CapturingRecordingFileFactory;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.containers.VncRecordingContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.org.apache.commons.io.FileUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * NOTE: The @ExtendWith annotation MUST come before @TestContainers in order for screenshots to work correctly.
 */
@SpringBootTest(classes = AdminUserInterfaceTest.TestConfiguration.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(AdminUserInterfaceTest.RecordingCallback.class)
@Testcontainers
class AdminUserInterfaceTest {

    private CapturingRecordingFileFactory recordingFactory = new CapturingRecordingFileFactory();

    @Value("${test.container.host:host.docker.internal}")
    private String testContainerHost;

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {CassandraAutoConfiguration.class})
    @ComponentScan("org.ntrloc.graph")
    static class TestConfiguration {
    }

    @DynamicPropertySource
    static void yamlProperties(DynamicPropertyRegistry registry) {
        var yaml = """
                spring:
                  main.web-application-type: reactive
                  graphql.schema.printer.enabled: true
                
                cache.tx-cache-size: 0
                graph.index.lucene.directory: target/db/lucene
                
                binary:
                  storage:
                    strategy: block
                    block:
                      location: target/storage-temp
                      autocreate: true
                logging:
                  level:
                    root: DEBUG
                
                """;
        Yaml parser = new Yaml();
        Map<String, Object> map = parser.load(yaml);
        flattenYamlProperties("", map, registry);
    }

    private static void flattenYamlProperties(String prefix, Map<String, Object> properties, DynamicPropertyRegistry registry) {
        properties.forEach((key, value) -> {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map) {
                flattenYamlProperties(fullKey, (Map<String, Object>) value, registry);
            } else {
                registry.add(fullKey, () -> value.toString());
            }
        });
    }

    @LocalServerPort
    private int port;

    @Container
    public BrowserWebDriverContainer<?> chrome = new BrowserWebDriverContainer<>()
            .withCapabilities((Capabilities) new ChromeOptions())
            .withRecordingMode(
                    BrowserWebDriverContainer.VncRecordingMode.RECORD_ALL,
                    new File("target/build/recordings"),
                    VncRecordingContainer.VncRecordingFormat.MP4
            )
            .withRecordingFileFactory(recordingFactory);

    private RemoteWebDriver driver;

    static class RecordingCallback implements AfterEachCallback {
        @Override
        public void afterEach(ExtensionContext context) {
            AdminUserInterfaceTest test = (AdminUserInterfaceTest) context.getRequiredTestInstance();
            String baseName = test.recordingFactory.getRecordingBaseName();
            if (baseName != null) {
                try {
                    File screenshot = ((TakesScreenshot) test.driver).getScreenshotAs(OutputType.FILE);
                    FileUtils.copyFile(screenshot, new File("target/build/screenshots/%s.png".formatted(baseName)));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @BeforeEach
    void setup() throws IOException {
        Files.createDirectories(Paths.get("target/build/recordings"));
        Files.createDirectories(Paths.get("target/build/screenshots"));

        org.testcontainers.Testcontainers.exposeHostPorts(port);
        driver = chrome.getWebDriver();
    }

    @Test
    void testAdminHomePage() throws IOException {
        var url = "http://%s:%d".formatted(testContainerHost, port);
        driver.get(url);
    }

}
