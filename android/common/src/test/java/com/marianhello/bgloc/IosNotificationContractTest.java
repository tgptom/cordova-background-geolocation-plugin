package com.marianhello.bgloc;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RunWith(RobolectricTestRunner.class)
public class IosNotificationContractTest {
    @Test
    public void usesAppGeofenceTrackingTransitionNotificationContract() throws IOException {
        Path repoRoot = findRepoRoot();
        Path iosHandler = repoRoot.resolve("ios/common/BackgroundGeolocation/MAURGeofenceTransitionHandler.m");
        Path readme = repoRoot.resolve("README.md");
        Path apiDoc = repoRoot.resolve("docs/api.md");

        String iosHandlerContent = new String(Files.readAllBytes(iosHandler), StandardCharsets.UTF_8);
        String readmeContent = new String(Files.readAllBytes(readme), StandardCharsets.UTF_8);
        String apiDocContent = new String(Files.readAllBytes(apiDoc), StandardCharsets.UTF_8);

        Assert.assertTrue(iosHandlerContent.contains(
                "NSString * const MAURGeofenceTrackingTransitionNotification = @\"AppGeofenceTrackingTransition\";"));
        Assert.assertTrue(readmeContent.contains("`AppGeofenceTrackingTransition`"));
        Assert.assertTrue(apiDocContent.contains("`AppGeofenceTrackingTransition`"));

        String legacyNotification = "PA" + "PA" + "GeofenceTrackingTransition";
        Assert.assertFalse(iosHandlerContent.contains(legacyNotification));
        Assert.assertFalse(readmeContent.contains(legacyNotification));
        Assert.assertFalse(apiDocContent.contains(legacyNotification));
    }

    private static Path findRepoRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && current != null; i++) {
            if (Files.exists(current.resolve("plugin.xml"))
                    && Files.exists(current.resolve("ios/common/BackgroundGeolocation/MAURGeofenceTransitionHandler.m"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Unable to locate repository root containing plugin.xml");
    }
}
