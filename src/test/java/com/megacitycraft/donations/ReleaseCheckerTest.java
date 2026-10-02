package com.megacitycraft.donations;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class ReleaseCheckerTest {
    private ReleaseChecker checker(int status, String json) {
        return new ReleaseChecker(() -> new ReleaseChecker.Response(status, json));
    }

    @Test void comparesVersionsNumericallyIncludingDoubleDigitComponents() {
        assertTrue(ReleaseChecker.isNewer("v1.0.2", "1.0.1"));
        assertTrue(ReleaseChecker.isNewer("v1.10.0", "1.9.9"));
        assertTrue(ReleaseChecker.isNewer("v2.0.0", "1.99.99"));
        assertFalse(ReleaseChecker.isNewer("v1.0.1", "1.0.1"));
        assertFalse(ReleaseChecker.isNewer("v1.0.0", "1.0.1"));
        assertFalse(ReleaseChecker.isNewer("v1.9.0", "1.10.0"));
    }

    @Test void ignoresPrereleaseTagsInvalidTagsAndBuildOnlyChanges() {
        assertFalse(ReleaseChecker.isNewer("v1.0.2-beta.1", "1.0.1"));
        assertFalse(ReleaseChecker.isNewer("release-next", "1.0.1"));
        assertFalse(ReleaseChecker.isNewer("v1.00.2", "1.0.1"));
        assertFalse(ReleaseChecker.isNewer("v1.0.1+build.2", "1.0.1"));
        assertTrue(ReleaseChecker.isNewer("v1.0.2+build.2", "1.0.1"));
    }

    @Test void publishedNewerReleaseIsReported() throws Exception {
        var result = checker(200, "{\"tag_name\":\"v1.0.2\",\"draft\":false,\"prerelease\":false}").findUpdate("1.0.1");
        assertEquals("v1.0.2", result.orElseThrow());
    }

    @Test void currentOrOlderReleaseProducesNoUpdate() throws Exception {
        for (String version : new String[]{"v1.0.0", "v1.0.1"}) {
            assertTrue(checker(200, "{\"tag_name\":\"" + version + "\",\"draft\":false,\"prerelease\":false}")
                    .findUpdate("1.0.1").isEmpty());
        }
    }

    @Test void draftsAndPrereleasesProduceNoUpdate() throws Exception {
        assertTrue(checker(200, "{\"tag_name\":\"v1.0.2\",\"draft\":true,\"prerelease\":false}").findUpdate("1.0.1").isEmpty());
        assertTrue(checker(200, "{\"tag_name\":\"v1.0.2\",\"draft\":false,\"prerelease\":true}").findUpdate("1.0.1").isEmpty());
    }

    @Test void missingPublicReleaseIsNormal() throws Exception {
        assertTrue(checker(404, "").findUpdate("1.0.1").isEmpty());
        assertNull(checker(404, "").check("1.0.1").latestTag());
    }

    @Test void currentVersionIsDistinguishedFromUnknownStatus() throws Exception {
        var result = checker(200, "{\"tag_name\":\"v1.0.1\",\"draft\":false,\"prerelease\":false}").check("1.0.1");
        assertEquals("v1.0.1", result.latestTag());
        assertFalse(result.updateAvailable());
        assertThrows(IOException.class, () -> checker(200,
                "{\"tag_name\":\"release-next\",\"draft\":false,\"prerelease\":false}").check("1.0.1"));
    }

    @Test void rateLimitsServerErrorsAndBrokenJsonAreRecoverable() {
        for (int status : new int[]{403, 429, 500}) {
            assertThrows(IOException.class, () -> checker(status, "").findUpdate("1.0.1"));
        }
        assertThrows(IOException.class, () -> checker(200, "not JSON").findUpdate("1.0.1"));
        assertThrows(IOException.class, () -> checker(200, "{}").findUpdate("1.0.1"));
    }

    @Test void networkFailuresArePropagatedForPluginToLogAndRetry() {
        ReleaseChecker offline = new ReleaseChecker(() -> { throw new IOException("timed out"); });
        assertThrows(IOException.class, () -> offline.findUpdate("1.0.1"));
    }
}
