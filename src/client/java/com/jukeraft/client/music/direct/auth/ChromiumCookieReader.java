package com.jukeraft.client.music.direct.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class ChromiumCookieReader {
    private static final Logger LOGGER = LoggerFactory.getLogger("jukeraft-ytdirect-auth");
    private static final String[] PROFILE_NAMES = {
            "Default", "Profile 1", "Profile 2", "Profile 3", "Profile 4", "Profile 5"
    };

    private ChromiumCookieReader() {
    }

    static Map<String, String> read(String browserName) throws Exception {
        Path userData = userDataRoot(browserName);
        if (userData == null || !Files.isDirectory(userData)) {
            return null;
        }
        byte[] key = loadMasterKey(userData);
        if (key == null) {
            return null;
        }
        for (String profileName : PROFILE_NAMES) {
            Path profile = userData.resolve(profileName);
            Path cookiesDb = profile.resolve("Network").resolve("Cookies");
            if (!Files.isRegularFile(cookiesDb)) {
                cookiesDb = profile.resolve("Cookies");
            }
            if (!Files.isRegularFile(cookiesDb)) {
                continue;
            }
            Map<String, String> found = readCookiesDb(cookiesDb, key);
            if (found != null && !found.isEmpty()) {
                return found;
            }
        }
        return null;
    }

    private static Path userDataRoot(String browserName) {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null) {
            return null;
        }
        return switch (browserName) {
            case "Chrome" -> Paths.get(localAppData, "Google", "Chrome", "User Data");
            case "Edge" -> Paths.get(localAppData, "Microsoft", "Edge", "User Data");
            default -> null;
        };
    }

    private static byte[] loadMasterKey(Path userData) throws Exception {
        Path localState = userData.resolve("Local State");
        if (!Files.isRegularFile(localState)) {
            return null;
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(localState, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonObject osCrypt = root.getAsJsonObject("os_crypt");
        if (osCrypt == null || !osCrypt.has("encrypted_key")) {
            return null;
        }
        byte[] encryptedKey = Base64.getDecoder().decode(osCrypt.get("encrypted_key").getAsString());

        byte[] dpapiBlob = java.util.Arrays.copyOfRange(encryptedKey, 5, encryptedKey.length);
        return dpapiUnprotectCurrentUser(dpapiBlob);
    }

    private static Map<String, String> readCookiesDb(Path cookiesDb, byte[] key) throws Exception {
        Path tempCopy = Files.createTempFile("jukeraft-chromium-cookies", ".sqlite");
        tempCopy.toFile().deleteOnExit();
        Files.copy(cookiesDb, tempCopy, StandardCopyOption.REPLACE_EXISTING);

        Class.forName("org.sqlite.JDBC");
        Map<String, String> result = new LinkedHashMap<>();
        String url = "jdbc:sqlite:" + tempCopy.toAbsolutePath() + "?immutable=1";
        boolean sawUndecryptable = false;
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT name, value, encrypted_value FROM cookies WHERE host_key LIKE '%youtube.com'");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("name");
                String plainValue = rs.getString("value");
                byte[] encrypted = rs.getBytes("encrypted_value");
                if (plainValue != null && !plainValue.isEmpty()) {
                    result.put(name, plainValue);
                    continue;
                }
                if (encrypted == null || encrypted.length < 3) {
                    continue;
                }
                String prefix = new String(encrypted, 0, 3, StandardCharsets.US_ASCII);
                if (prefix.equals("v20")) {
                    sawUndecryptable = true;
                    continue;
                }
                if (prefix.equals("v10")) {
                    String decrypted = decryptAesGcm(encrypted, key);
                    if (decrypted != null) {
                        result.put(name, decrypted);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(tempCopy);
        }
        if (sawUndecryptable && result.isEmpty()) {
            LOGGER.debug("Cookies are App-Bound-Encrypted (v20); can't auto-read this browser profile");
        }
        return result;
    }

    private static String decryptAesGcm(byte[] encrypted, byte[] key) {
        try {
            byte[] nonce = java.util.Arrays.copyOfRange(encrypted, 3, 15);
            byte[] ciphertextAndTag = java.util.Arrays.copyOfRange(encrypted, 15, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] plain = cipher.doFinal(ciphertextAndTag);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] dpapiUnprotectCurrentUser(byte[] blob) throws IOException, InterruptedException {
        String base64 = Base64.getEncoder().encodeToString(blob);
        String script = "$b = [Convert]::FromBase64String('" + base64 + "'); "
                + "Add-Type -AssemblyName System.Security; "
                + "$d = [System.Security.Cryptography.ProtectedData]::Unprotect($b, $null, "
                + "[System.Security.Cryptography.DataProtectionScope]::CurrentUser); "
                + "[Convert]::ToBase64String($d)";
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
        Process proc = pb.start();
        String out;
        try (var in = proc.getInputStream()) {
            out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0 || out.isEmpty()) {
            throw new IOException("DPAPI unprotect of the Chromium master key failed");
        }
        return Base64.getDecoder().decode(out);
    }
}
