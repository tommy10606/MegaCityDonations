package com.megacitycraft.donations;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/** Checks stable semantic versions using GitHub's public latest-release API. */
final class ReleaseChecker {
    static final String RELEASES_URL = "https://github.com/tommy10606/MegaCityDonations/releases/latest";
    private static final URI API = URI.create("https://api.github.com/repos/tommy10606/MegaCityDonations/releases/latest");
    private static final Pattern VERSION = Pattern.compile(
            "^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");
    private final Fetcher fetcher;

    @FunctionalInterface interface Fetcher { Response fetch() throws IOException; }
    record Response(int status, String body) { }
    record Result(String latestTag, boolean updateAvailable) { }

    ReleaseChecker() { this(ReleaseChecker::fetchLatest); }
    ReleaseChecker(Fetcher fetcher) { this.fetcher = fetcher; }

    Optional<String> findUpdate(String installedVersion) throws IOException {
        Result result = check(installedVersion);
        return result.updateAvailable() ? Optional.of(result.latestTag()) : Optional.empty();
    }

    Result check(String installedVersion) throws IOException {
        Response response = fetcher.fetch();
        if (response.status() == 404) return new Result(null, false); // No public release yet.
        if (response.status() == 403 || response.status() == 429)
            throw new IOException("GitHub denied or rate-limited the request (HTTP " + response.status() + ")");
        if (response.status() != 200) throw new IOException("GitHub returned HTTP " + response.status());
        try {
            JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
            if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean())
                return new Result(null, false);
            String tag = release.get("tag_name").getAsString();
            if (!VERSION.matcher(tag).matches() || !VERSION.matcher(installedVersion).matches())
                throw new IOException("Release tags must use stable versions such as v1.0.1");
            return new Result(tag, isNewer(tag, installedVersion));
        } catch (RuntimeException error) {
            throw new IOException("GitHub returned invalid release data", error);
        }
    }

    static boolean isNewer(String candidate, String installed) {
        var newer = VERSION.matcher(candidate);
        var current = VERSION.matcher(installed);
        if (!newer.matches() || !current.matches()) return false;
        for (int index = 1; index <= 3; index++) {
            int comparison = new BigInteger(newer.group(index)).compareTo(new BigInteger(current.group(index)));
            if (comparison != 0) return comparison > 0;
        }
        return false;
    }

    private static Response fetchLatest() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) API.toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10");
        connection.setRequestProperty("User-Agent", "MegaCityDonations-update-checker");
        try {
            int status = connection.getResponseCode();
            if (status != 200) return new Response(status, "");
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(1_048_577);
                if (bytes.length > 1_048_576) throw new IOException("GitHub release response exceeded 1 MiB");
                return new Response(status, new String(bytes, StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
