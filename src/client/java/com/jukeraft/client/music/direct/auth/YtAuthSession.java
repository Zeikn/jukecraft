package com.jukeraft.client.music.direct.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class YtAuthSession {
    private static final Logger LOGGER = LoggerFactory.getLogger("jukeraft-ytdirect-auth");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getGameDir().resolve("jukeraft-auth.json");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jukeraft-ytdirect-auth-detect");
        t.setDaemon(true);
        return t;
    });

    public enum Source { NONE, FIREFOX, CHROME, EDGE, PASTED }

    public enum Status { CHECKING, LOGGED_OUT, LOGGED_IN }

    private static final class Account {
        String id;
        Source source = Source.NONE;
        Map<String, String> cookies = new LinkedHashMap<>();
        String accountName;
        String accountHandle;
        String accountPhotoUrl;
        boolean loadFailed;
    }

    public record AccountInfo(String id, Source source, String name, String handle, String photoUrl,
                               boolean active, boolean loadFailed) {
    }

    private static final class Data {
        List<Account> accounts = new ArrayList<>();
        String activeAccountId;
    }

    private static volatile Data data = new Data();
    private static volatile Status status = Status.CHECKING;
    private static volatile boolean autoDetectStarted;

    private YtAuthSession() {
    }

    public static synchronized void init() {
        if (autoDetectStarted) {
            return;
        }
        autoDetectStarted = true;
        data = load();
        if (!data.accounts.isEmpty()) {
            status = getActiveAccountInternal() != null ? Status.LOGGED_IN : Status.LOGGED_OUT;
            LOGGER.info("Restored {} saved YouTube account(s)", data.accounts.size());
            for (Account a : data.accounts) {
                refreshAccountInfo(a);
            }
            return;
        }
        status = Status.CHECKING;
        EXECUTOR.submit(YtAuthSession::runAutoDetect);
    }

    private static void runAutoDetect() {
        try {
            BrowserCookieReader.Found found = BrowserCookieReader.autoDetect();
            if (found != null) {
                addAccount(found.source(), found.cookies());
                LOGGER.info("Auto-detected an existing YouTube login via {}", found.source());
                return;
            }
        } catch (Exception e) {
            LOGGER.warn("Browser cookie auto-detect failed", e);
        }
        status = Status.LOGGED_OUT;
    }

    public static boolean setPastedCookieHeader(String raw) {
        Map<String, String> parsed = parseCookieHeader(raw);
        if (!parsed.containsKey("SAPISID") && !parsed.containsKey("__Secure-3PAPISID")) {
            return false;
        }
        addAccount(Source.PASTED, parsed);
        return true;
    }

    private static synchronized void addAccount(Source source, Map<String, String> cookies) {
        Account account = new Account();
        account.id = UUID.randomUUID().toString();
        account.source = source;
        account.cookies = new LinkedHashMap<>(cookies);
        data.accounts.add(account);
        data.activeAccountId = account.id;
        status = Status.LOGGED_IN;
        save();
        refreshAccountInfo(account);
    }

    public static List<AccountInfo> getAccounts() {
        List<AccountInfo> out = new ArrayList<>();
        for (Account a : data.accounts) {
            out.add(new AccountInfo(a.id, a.source, a.accountName, a.accountHandle, a.accountPhotoUrl,
                    a.id.equals(data.activeAccountId), a.loadFailed));
        }
        return out;
    }

    public static void retryAccountInfo(String id) {
        for (Account a : data.accounts) {
            if (a.id.equals(id)) {
                a.loadFailed = false;
                refreshAccountInfo(a);
                return;
            }
        }
    }

    public static void setActiveAccount(String id) {
        for (Account a : data.accounts) {
            if (a.id.equals(id)) {
                data.activeAccountId = id;
                status = Status.LOGGED_IN;
                save();
                return;
            }
        }
    }

    public static void removeAccount(String id) {
        data.accounts.removeIf(a -> a.id.equals(id));
        if (id.equals(data.activeAccountId)) {
            data.activeAccountId = null;
            status = Status.LOGGED_OUT;
        }
        save();
    }

    public static void logout() {
        data.activeAccountId = null;
        status = Status.LOGGED_OUT;
        save();
    }

    private static void refreshAccountInfo(Account account) {
        String cookieHeader = cookieHeaderOf(account.cookies);
        String authorization = sapisidHashOf(account.cookies, "https://music.youtube.com");
        if (cookieHeader == null || authorization == null) {
            account.loadFailed = true;
            save();
            return;
        }
        YtAccountInfo.fetch(cookieHeader, authorization).thenAccept(info -> {
            if (!data.accounts.contains(account)) {
                return;
            }
            if (info == null) {
                account.loadFailed = true;
            } else {
                account.loadFailed = false;
                account.accountName = info.name();
                account.accountHandle = info.handle();
                account.accountPhotoUrl = info.photoUrl();
            }
            save();
        });
    }

    public static Status getStatus() {
        return status;
    }

    public static boolean isLoggedIn() {
        return getActiveAccountInternal() != null;
    }

    public static Source getSource() {
        Account active = getActiveAccountInternal();
        return active != null ? active.source : Source.NONE;
    }

    public static String getAccountName() {
        Account active = getActiveAccountInternal();
        return active != null ? active.accountName : null;
    }

    public static String getAccountHandle() {
        Account active = getActiveAccountInternal();
        return active != null ? active.accountHandle : null;
    }

    public static String getAccountPhotoUrl() {
        Account active = getActiveAccountInternal();
        return active != null ? active.accountPhotoUrl : null;
    }

    public static String getCookieHeader() {
        Account active = getActiveAccountInternal();
        return active != null ? cookieHeaderOf(active.cookies) : null;
    }

    public static String getSapisidHashAuthorization(String origin) {
        Account active = getActiveAccountInternal();
        return active != null ? sapisidHashOf(active.cookies, origin) : null;
    }

    private static Account getActiveAccountInternal() {
        if (data.activeAccountId == null) {
            return null;
        }
        for (Account a : data.accounts) {
            if (a.id.equals(data.activeAccountId)) {
                return a;
            }
        }
        return null;
    }

    private static String cookieHeaderOf(Map<String, String> cookies) {
        if (cookies.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String sapisidHashOf(Map<String, String> cookies, String origin) {
        String sapisid = cookies.get("SAPISID");
        if (sapisid == null) {
            sapisid = cookies.get("__Secure-3PAPISID");
        }
        if (sapisid == null) {
            return null;
        }
        long ts = System.currentTimeMillis() / 1000L;
        String toHash = ts + " " + sapisid + " " + origin;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(toHash.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return "SAPISIDHASH " + ts + "_" + hex;
        } catch (NoSuchAlgorithmException e) {

            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> parseCookieHeader(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return out;
    }

    private static Data load() {
        if (!Files.exists(FILE)) {
            return new Data();
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Data>() { }.getType();
            Data loaded = GSON.fromJson(reader, type);
            return loaded != null ? loaded : new Data();
        } catch (IOException | RuntimeException e) {
            return new Data();
        }
    }

    private static synchronized void save() {
        try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (IOException e) {
            LOGGER.warn("Failed to persist YouTube session(s)", e);
        }
    }
}
