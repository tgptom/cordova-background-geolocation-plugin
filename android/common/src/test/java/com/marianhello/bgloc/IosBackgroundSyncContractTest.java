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
public class IosBackgroundSyncContractTest {
    @Test
    public void iosBackgroundSyncInitializesAndSynchronizesTaskTracking() throws IOException {
        Path repoRoot = findRepoRoot();
        String backgroundSync = read(repoRoot.resolve("ios/common/BackgroundGeolocation/MAURBackgroundSync.m"));

        Assert.assertTrue(backgroundSync.contains("tasks = [[NSMutableArray alloc] init];"));
        Assert.assertTrue(backgroundSync.contains("@synchronized (tasks)"));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path findRepoRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && current != null; i++) {
            if (Files.exists(current.resolve("plugin.xml"))
                    && Files.exists(current.resolve("ios/common/BackgroundGeolocation/MAURBackgroundSync.m"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Unable to locate repository root containing plugin.xml");
    }
}
