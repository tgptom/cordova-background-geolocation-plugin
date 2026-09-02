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
public class GeofenceCompanionContractConsistencyTest {
    @Test
    public void companionContractReferenceIsConsistentAcrossRuntimeAndPublicTyping() throws IOException {
        Path repoRoot = findRepoRoot();
        Path androidHandler = repoRoot.resolve("android/common/src/main/java/com/marianhello/bgloc/GeofenceTransitionHandler.java");
        Path typings = repoRoot.resolve("www/BackgroundGeolocation.d.ts");

        String androidHandlerContent = new String(Files.readAllBytes(androidHandler), StandardCharsets.UTF_8);
        String typingsContent = new String(Files.readAllBytes(typings), StandardCharsets.UTF_8);

        Assert.assertTrue(androidHandlerContent.contains("PR #11"));
        Assert.assertTrue(typingsContent.contains("PR #11"));
        Assert.assertFalse(typingsContent.contains("PR #9 hardened transition contract"));
    }

    private static Path findRepoRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && current != null; i++) {
            if (Files.exists(current.resolve("plugin.xml"))
                    && Files.exists(current.resolve("www/BackgroundGeolocation.d.ts"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Unable to locate repository root containing plugin.xml");
    }
}
