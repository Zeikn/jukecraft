package com.jukeraft.client.music.direct.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

final class FirefoxCookieReader {
    private FirefoxCookieReader() {
    }

    static Map<String, String> read() throws Exception {
        Path profile = findDefaultProfile();
        if (profile == null) {
            return null;
        }
        Path db = profile.resolve("cookies.sqlite");
        if (!Files.isRegularFile(db)) {
            return null;
        }
        Path tempCopy = Files.createTempFile("jukeraft-ff-cookies", ".sqlite");
        tempCopy.toFile().deleteOnExit();
        Files.copy(db, tempCopy, StandardCopyOption.REPLACE_EXISTING);

        Class.forName("org.sqlite.JDBC");
        Map<String, String> result = new LinkedHashMap<>();
        String url = "jdbc:sqlite:" + tempCopy.toAbsolutePath() + "?immutable=1";
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name, value FROM moz_cookies WHERE host LIKE '%youtube.com'")) {
            while (rs.next()) {
                result.put(rs.getString("name"), rs.getString("value"));
            }
        } finally {
            Files.deleteIfExists(tempCopy);
        }
        return result;
    }

    private static Path findDefaultProfile() throws IOException {
        String appData = System.getenv("APPDATA");
        if (appData == null) {
            return null;
        }
        Path firefoxDir = Paths.get(appData, "Mozilla", "Firefox");
        Path ini = firefoxDir.resolve("profiles.ini");
        if (Files.isRegularFile(ini)) {
            Path fromIni = parseProfilesIni(firefoxDir, ini);
            if (fromIni != null) {
                return fromIni;
            }
        }
        Path profilesDir = firefoxDir.resolve("Profiles");
        if (!Files.isDirectory(profilesDir)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profilesDir, "*.default-release*")) {
            for (Path p : stream) {
                return p;
            }
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profilesDir, "*.default*")) {
            for (Path p : stream) {
                return p;
            }
        }
        return null;
    }

    private static Path parseProfilesIni(Path firefoxDir, Path ini) throws IOException {
        String currentPath = null;
        boolean currentRelative = true;
        boolean currentDefault = false;
        String bestPath = null;
        boolean bestRelative = true;
        boolean haveAny = false;

        for (String line : Files.readAllLines(ini, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.startsWith("[")) {
                if (currentPath != null && (currentDefault || !haveAny)) {
                    bestPath = currentPath;
                    bestRelative = currentRelative;
                    haveAny = true;
                    if (currentDefault) {
                        break;
                    }
                }
                currentPath = null;
                currentRelative = true;
                currentDefault = false;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (key.equalsIgnoreCase("Path")) {
                currentPath = value;
            } else if (key.equalsIgnoreCase("IsRelative")) {
                currentRelative = value.equals("1");
            } else if (key.equalsIgnoreCase("Default")) {
                currentDefault = value.equals("1");
            }
        }
        if (currentPath != null && (currentDefault || !haveAny)) {
            bestPath = currentPath;
            bestRelative = currentRelative;
        }
        if (bestPath == null) {
            return null;
        }
        return bestRelative ? firefoxDir.resolve(bestPath) : Paths.get(bestPath);
    }
}
