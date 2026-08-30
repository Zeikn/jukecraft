package com.jukeraft.client.music;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class EmbeddedBridgeServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("jukeraft-bridge");
    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final int port;
    private final String displayName;
    private final Consumer<String> onMessage;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "jukeraft-embedded-ws");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ServerSocket serverSocket;
    private volatile Socket clientSocket;
    private volatile OutputStream clientOut;
    private volatile boolean stopped;

    EmbeddedBridgeServer(int port, String displayName, Consumer<String> onMessage) {
        this.port = port;
        this.displayName = displayName;
        this.onMessage = onMessage;
    }

    boolean start() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException e) {
            return false;
        }
        LOGGER.info("Hosting {} bridge directly on ws://127.0.0.1:{} (no companion app found)", displayName, port);
        executor.submit(this::acceptLoop);
        return true;
    }

    void stop() {
        stopped = true;
        closeQuietly(serverSocket);
        closeQuietly(clientSocket);
        executor.shutdownNow();
    }

    boolean hasClient() {
        return clientOut != null;
    }

    void send(String json) {
        OutputStream out = clientOut;
        if (out == null) {
            return;
        }
        try {
            synchronized (this) {
                writeTextFrame(out, json);
            }
        } catch (IOException e) {
            clientOut = null;
        }
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket socket = serverSocket.accept();
                closeQuietly(clientSocket);
                clientSocket = socket;
                handshakeAndServe(socket);
            } catch (IOException e) {
                if (!stopped) {
                    LOGGER.warn("Embedded WS accept failed: {}", e.toString());
                }
            }
        }
    }

    private void handshakeAndServe(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String key = readHandshakeKey(in);
            if (key == null) {
                closeQuietly(socket);
                return;
            }
            String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + WS_MAGIC).getBytes(StandardCharsets.UTF_8)));
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            clientOut = out;
            LOGGER.info("Browser extension connected directly to the embedded {} bridge", displayName);

            readFrameLoop(in);
        } catch (Exception e) {

        } finally {
            if (clientSocket == socket) {
                clientOut = null;
            }
            closeQuietly(socket);
        }
    }

    private String readHandshakeKey(InputStream in) throws IOException {
        StringBuilder request = new StringBuilder();
        int cur;
        while ((cur = in.read()) != -1) {
            request.append((char) cur);
            int len = request.length();
            if (len >= 4 && request.charAt(len - 4) == '\r' && request.charAt(len - 3) == '\n'
                    && request.charAt(len - 2) == '\r' && request.charAt(len - 1) == '\n') {
                break;
            }
        }
        for (String line : request.toString().split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Key")) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private void readFrameLoop(InputStream in) throws IOException {
        while (!stopped) {
            int b1 = in.read();
            if (b1 == -1) {
                return;
            }
            int opcode = b1 & 0x0F;
            int b2 = in.read();
            if (b2 == -1) {
                return;
            }
            boolean masked = (b2 & 0x80) != 0;
            long len = b2 & 0x7F;
            if (len == 126) {
                len = (readByte(in) << 8) | readByte(in);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte(in);
                }
            }
            byte[] mask = new byte[4];
            if (masked && in.readNBytes(mask, 0, 4) != 4) {
                return;
            }
            byte[] payload = new byte[(int) len];
            int read = 0;
            while (read < payload.length) {
                int n = in.read(payload, read, payload.length - read);
                if (n == -1) {
                    return;
                }
                read += n;
            }
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }

            if (opcode == 0x8) {
                return;
            } else if (opcode == 0x1) {
                onMessage.accept(new String(payload, StandardCharsets.UTF_8));
            }

        }
    }

    private int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException();
        }
        return b;
    }

    private static void writeTextFrame(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        out.write(0x81);
        int len = payload.length;
        if (len < 126) {
            out.write(len);
        } else if (len < 65536) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((len >> shift) & 0xFF);
            }
        }
        out.write(payload);
        out.flush();
    }

    private static void closeQuietly(Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (IOException ignored) {
        }
    }
}
