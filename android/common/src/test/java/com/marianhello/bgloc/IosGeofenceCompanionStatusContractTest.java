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
public class IosGeofenceCompanionStatusContractTest {
    @Test
    public void iosCompanionStatusUsesCapabilityMetadataInsteadOfFabricatedPendingOwners() throws IOException {
        Path repoRoot = findRepoRoot();
        String pluginContent = read(repoRoot.resolve("ios/CDVBackgroundGeolocation/CDVBackgroundGeolocation.m"));
        String facadeHeader = read(repoRoot.resolve("ios/common/BackgroundGeolocation/MAURBackgroundGeolocationFacade.h"));
        String facadeContent = read(repoRoot.resolve("ios/common/BackgroundGeolocation/MAURBackgroundGeolocationFacade.m"));
        String typings = read(repoRoot.resolve("www/BackgroundGeolocation.d.ts"));
        String apiDoc = read(repoRoot.resolve("docs/api.md"));

        Assert.assertTrue(pluginContent.contains("dict[@\"statusSchemaVersion\"]"));
        Assert.assertTrue(pluginContent.contains("dict[@\"pendingOwnersSupported\"]"));
        Assert.assertFalse(pluginContent.contains("dict[@\"pendingStartOwner\"] = @0;"));
        Assert.assertFalse(pluginContent.contains("dict[@\"pendingStopOwner\"] = @0;"));

        Assert.assertTrue(facadeHeader.contains("- (BOOL) supportsCompanionPendingOwners;"));
        Assert.assertTrue(facadeHeader.contains("- (NSInteger) geofenceCompanionStatusSchemaVersion;"));
        Assert.assertTrue(facadeContent.contains("- (BOOL) supportsCompanionPendingOwners"));
        Assert.assertTrue(facadeContent.contains("return NO;"));
        Assert.assertTrue(facadeContent.contains("- (NSInteger) geofenceCompanionStatusSchemaVersion"));
        Assert.assertTrue(facadeContent.contains("return 2;"));

        Assert.assertTrue(typings.contains("statusSchemaVersion: number;"));
        Assert.assertTrue(typings.contains("pendingOwnersSupported: boolean;"));
        Assert.assertTrue(typings.contains("pendingStartOwner?: number;"));
        Assert.assertTrue(typings.contains("pendingStopOwner?: number;"));

        Assert.assertTrue(apiDoc.contains("statusSchemaVersion"));
        Assert.assertTrue(apiDoc.contains("pendingOwnersSupported"));
        Assert.assertTrue(apiDoc.contains("pendingStartOwner"));
        Assert.assertTrue(apiDoc.contains("pendingStopOwner"));
    }

    @Test
    public void iosStopForGeofenceKeepsManualOwnershipConflictAndIdempotentNoopPath() throws IOException {
        Path repoRoot = findRepoRoot();
        String facadeContent = read(repoRoot.resolve("ios/common/BackgroundGeolocation/MAURBackgroundGeolocationFacade.m"));
        String pluginContent = read(repoRoot.resolve("ios/CDVBackgroundGeolocation/CDVBackgroundGeolocation.m"));
        String typings = read(repoRoot.resolve("www/BackgroundGeolocation.d.ts"));
        String apiDoc = read(repoRoot.resolve("docs/api.md"));

        Assert.assertTrue(facadeContent.contains("owner == MAURTrackingOwnerGeofence && isStarted && [self trackingOwner] == MAURTrackingOwnerManual"));
        Assert.assertTrue(facadeContent.contains("code:MAURBGOwnershipConflict"));
        Assert.assertTrue(facadeContent.contains("return YES;"));

        Assert.assertTrue(pluginContent.contains("BOOL stopped = [facade stopForOwner:MAURTrackingOwnerGeofence error:&error];"));
        Assert.assertTrue(pluginContent.contains("[self sendEvent:@\"stop\"];"));
        Assert.assertTrue(typings.contains("ownership-conflict error (`code: 1005`)"));
        Assert.assertTrue(apiDoc.contains("ownership conflict (`code: 1005`)"));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path findRepoRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && current != null; i++) {
            if (Files.exists(current.resolve("plugin.xml"))
                    && Files.exists(current.resolve("ios/CDVBackgroundGeolocation/CDVBackgroundGeolocation.m"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Unable to locate repository root containing plugin.xml");
    }
}
