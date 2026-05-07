package org.ntrloc.graph.ui;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.shaded.org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

@Testcontainers
class AdminUserInterfaceTest {

    @BeforeAll
    static void setup() throws IOException {
        Files.createDirectories(Paths.get("target/build/recordings"));
        Files.createDirectories(Paths.get("target/build/screenshots"));
    }

    @Container
    public BrowserWebDriverContainer<?> chrome = new BrowserWebDriverContainer<>()
            .withCapabilities((Capabilities) new ChromeOptions())
            .withRecordingMode(BrowserWebDriverContainer.VncRecordingMode.RECORD_ALL, new File("target/build/recordings"));

    @Test
    void testGoogleSearch() throws IOException {
        RemoteWebDriver driver = chrome.getWebDriver();
        driver.get("https://google.com");
        // Your test logic here

        // Capture screenshot at end of test
        File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
        FileUtils.copyFile(screenshot, new File("target/build/screenshots/test-result.png"));
    }

}
