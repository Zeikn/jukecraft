package com.jukeraft.client.music;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BridgeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("jukeraft-bridge");
    private static final long RECONNECT_DELAY_SECONDS = 3;

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "jukeraft-ytm-bridge");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicReference<PlaybackState> STATE = new AtomicReference<>();

    private static final FxState FX_STATE_YTM = new FxState();
    private static final FxState FX_STATE_SPOTIFY = new FxState();

    private static volatile Provider provider = Provider.YTM;
    private static volatile WebSocket webSocket;
    private static volatile EmbeddedBridgeServer embeddedServer;
    private static volatile boolean shuttingDown;
    private static volatile boolean triedEmbeddedFallback;
    private static final StringBuilder MESSAGE_BUFFER = new StringBuilder();

    private static final AtomicInteger GENERATION = new AtomicInteger();

    private BridgeClient() {
    }

    public static void start(Provider initialProvider) {
        shuttingDown = false;
        provider = initialProvider;
        connect(GENERATION.incrementAndGet());
    }

    public static void stop() {
        shuttingDown = true;
        GENERATION.incrementAndGet();
        teardown();
    }

    public static void switchProvider(Provider newProvider) {
        if (newProvider == provider || shuttingDown) {
            return;
        }
        int generation = GENERATION.incrementAndGet();
        teardown();
        provider = newProvider;
        triedEmbeddedFallback = false;
        STATE.set(null);
        LOGGER.info("Switching bridge to {}", newProvider.displayName);
        connect(generation);
    }

    private static void teardown() {
        WebSocket ws = webSocket;
        if (ws != null) {
            ws.abort();
        }
        webSocket = null;
        EmbeddedBridgeServer server = embeddedServer;
        if (server != null) {
            server.stop();
        }
        embeddedServer = null;
    }

    public static Provider getProvider() {
        return provider;
    }

    public static PlaybackState getState() {
        return STATE.get();
    }

    public static FxState getFxState() {
        return fxStateFor(provider);
    }

    private static FxState fxStateFor(Provider p) {
        return p == Provider.YTM ? FX_STATE_YTM : FX_STATE_SPOTIFY;
    }

    public static boolean isConnected() {
        EmbeddedBridgeServer server = embeddedServer;
        if (server != null && server.hasClient()) {
            return true;
        }
        WebSocket ws = webSocket;
        return ws != null && !ws.isOutputClosed() && !ws.isInputClosed();
    }

    public static void sendCommand(String command) {
        sendCommand(command, null);
    }

    public static void sendCommand(String command, JsonObject payload) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "command");
        message.addProperty("command", command);
        if (payload != null) {
            message.add("payload", payload);
        }

        EmbeddedBridgeServer server = embeddedServer;
        if (server != null && server.hasClient()) {
            server.send(message.toString());
            return;
        }

        WebSocket ws = webSocket;
        if (ws == null) {
            return;
        }
        try {
            ws.sendText(message.toString(), true);
        } catch (Exception ignored) {

        }
    }

    private static void connect(int generation) {
        if (shuttingDown || generation != GENERATION.get()) {
            return;
        }
        URI bridgeUri = URI.create("ws://127.0.0.1:" + provider.port + "/display");
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<WebSocket> future = client.newWebSocketBuilder().buildAsync(bridgeUri, new BridgeListener(generation));
        future.whenComplete((ws, error) -> {
            if (error != null) {
                scheduleReconnect(generation);
            }
        });
    }

    private static void scheduleReconnect(int generation) {
        if (generation != GENERATION.get()) {
            return;
        }
        if (webSocket != null) {
            LOGGER.info("Disconnected from {} companion app; retrying in {}s", provider.displayName, RECONNECT_DELAY_SECONDS);
        }
        webSocket = null;
        STATE.set(null);
        if (shuttingDown) {
            return;
        }

        if (!triedEmbeddedFallback) {
            triedEmbeddedFallback = true;
            Provider forProvider = provider;
            EmbeddedBridgeServer server = new EmbeddedBridgeServer(forProvider.port, forProvider.displayName, BridgeClient::handleMessage);
            if (server.start()) {
                if (generation != GENERATION.get()) {
                    server.stop();
                    return;
                }
                embeddedServer = server;
                return;
            }
            LOGGER.warn("Could not host embedded {} bridge on port {} either; will keep retrying the companion app",
                    forProvider.displayName, forProvider.port);
        }

        EXECUTOR.schedule(() -> connect(generation), RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private static final class BridgeListener implements WebSocket.Listener {
        private final int generation;

        BridgeListener(int generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (generation != GENERATION.get()) {
                webSocket.abort();
                return;
            }
            LOGGER.info("Connected to {} companion app", provider.displayName);
            BridgeClient.webSocket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            MESSAGE_BUFFER.append(data);
            webSocket.request(1);
            if (last) {
                String message = MESSAGE_BUFFER.toString();
                MESSAGE_BUFFER.setLength(0);
                if (generation == GENERATION.get()) {
                    handleMessage(message);
                }
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            scheduleReconnect(generation);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            scheduleReconnect(generation);
        }
    }

    private static void handleMessage(String rawJson) {
        JsonElement root;
        try {
            root = JsonParser.parseString(rawJson);
        } catch (Exception e) {
            return;
        }
        if (!root.isJsonObject()) {
            return;
        }
        JsonObject object = root.getAsJsonObject();
        String type = optString(object, "type", null);
        if ("state".equals(type)) {
            JsonElement stateEl = object.get("state");
            STATE.set(stateEl == null || stateEl.isJsonNull() ? null : parseState(stateEl.getAsJsonObject()));
        } else if ("command".equals(type) && "fx-sync".equals(optString(object, "command", null))) {
            JsonElement payloadEl = object.get("payload");
            if (payloadEl != null && payloadEl.isJsonObject()) {
                applyFxSync(payloadEl.getAsJsonObject());
            }
        }
    }

    private static void applyFxSync(JsonObject payload) {

        FxState fxState = fxStateFor(provider);
        JsonElement eqEl = payload.get("eq");
        if (eqEl != null && eqEl.isJsonArray()) {
            JsonArray eqArray = eqEl.getAsJsonArray();
            for (int i = 0; i < FxState.BAND_COUNT && i < eqArray.size(); i++) {
                fxState.eq[i] = eqArray.get(i).getAsFloat();
            }
        }
        fxState.reverbWet = optFloat(payload, "reverbWet", fxState.reverbWet);
        fxState.width = optFloat(payload, "width", fxState.width);
    }

    private static PlaybackState parseState(JsonObject o) {
        List<PlaybackState.QueueItem> queue = new ArrayList<>();
        JsonElement queueEl = o.get("queue");
        if (queueEl != null && queueEl.isJsonArray()) {
            for (JsonElement el : queueEl.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject q = el.getAsJsonObject();
                queue.add(new PlaybackState.QueueItem(
                        (int) optDouble(q, "index", queue.size()),
                        optString(q, "title", ""),
                        optString(q, "artist", ""),
                        optString(q, "thumbnailUrl", ""),
                        optBool(q, "isCurrent", false)
                ));
            }
        }

        return new PlaybackState(
                optString(o, "title", ""),
                optString(o, "artist", ""),
                optString(o, "thumbnailUrl", ""),
                optBool(o, "isPlaying", false),
                optDouble(o, "currentTime", 0),
                optDouble(o, "duration", 0),
                optDouble(o, "volume", 1),
                optString(o, "repeatMode", "off"),
                optBool(o, "shuffleActive", false),
                queue
        );
    }

    private static String optString(JsonObject o, String key, String fallback) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsString();
    }

    private static double optDouble(JsonObject o, String key, double fallback) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsDouble();
    }

    private static float optFloat(JsonObject o, String key, float fallback) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsFloat();
    }

    private static boolean optBool(JsonObject o, String key, boolean fallback) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? fallback : el.getAsBoolean();
    }
}
