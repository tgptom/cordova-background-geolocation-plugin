package com.marianhello.bgloc;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@RunWith(RobolectricTestRunner.class)
public class PluginXmlSourceRegistrationTest {
    @Test
    public void pluginXmlListsEveryCommonAndroidJavaSourceExactlyOnce() throws IOException {
        Path repoRoot = findRepoRoot();
        Path pluginXmlPath = repoRoot.resolve("plugin.xml");
        String pluginXml = new String(Files.readAllBytes(pluginXmlPath), StandardCharsets.UTF_8);

        Assert.assertEquals(1, countMatches(pluginXml,
                "<source-file\\s+src=\"android/common/src/main/java/com/marianhello/bgloc/TrackingLifecycleCoordinator.java\"\\s+target-dir=\"src/com/marianhello/bgloc\"\\s*/>"));
        Assert.assertEquals(1, countMatches(pluginXml,
                "<source-file\\s+src=\"android/common/src/main/java/com/marianhello/bgloc/service/ServiceLifecycleStateStore.java\"\\s+target-dir=\"src/com/marianhello/bgloc/service\"\\s*/>"));

        Map<String, Integer> sourceCounts = new HashMap<String, Integer>();
        Pattern sourcePattern = Pattern.compile("<source-file\\s+src=\"(android/common/src/main/java/[^\"]+\\.java)\"\\s+target-dir=\"[^\"]+\"\\s*/>");
        Matcher matcher = sourcePattern.matcher(pluginXml);
        while (matcher.find()) {
            String source = matcher.group(1);
            Integer count = sourceCounts.get(source);
            sourceCounts.put(source, count == null ? 1 : count + 1);
        }

        Path sourceRoot = repoRoot.resolve("android/common/src/main/java");
        try (Stream<Path> files = Files.walk(sourceRoot, Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)) {
            Iterator<Path> iterator = files.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (!Files.isRegularFile(file) || !file.toString().endsWith(".java")) {
                    continue;
                }
                String relativeSourcePath = toUnixPath(repoRoot.relativize(file));
                Integer count = sourceCounts.get(relativeSourcePath);
                Assert.assertEquals("Missing or duplicate source registration for " + relativeSourcePath, Integer.valueOf(1), count);
            }
        }
    }

    private static int countMatches(String value, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(value);
        int count = 0;
        while (matcher.find()) {
            count += 1;
        }
        return count;
    }

    private static Path findRepoRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 8 && current != null; i++) {
            if (Files.exists(current.resolve("plugin.xml"))
                    && Files.exists(current.resolve("android/common/src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Unable to locate repository root containing plugin.xml");
    }

    private static String toUnixPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
