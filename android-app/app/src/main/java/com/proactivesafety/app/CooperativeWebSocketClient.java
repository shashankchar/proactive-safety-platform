package com.proactivesafety.app;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocketFactory;

final class CooperativeWebSocketClient {
    interface Listener {
        void onMessage(String message);
        void onClosed();
    }

    private final URI uri;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private Socket socket;
    private BufferedInputStream input;
    private OutputStream output;
    private volatile boolean connected = false;

    CooperativeWebSocketClient(String url, Listener listener) throws Exception {
        this.uri = URI.create(url);
        this.listener = listener;
    }

    void connect() {
        new Thread(() -> {
            try {
                int port = uri.getPort() > 0 ? uri.getPort() : ("wss".equals(uri.getScheme()) ? 443 : 80);
                if ("wss".equals(uri.getScheme())) {
                    socket = SSLSocketFactory.getDefault().createSocket(uri.getHost(), port);
                } else {
                    socket = new Socket(uri.getHost(), port);
                }
                socket.setTcpNoDelay(true);
                input = new BufferedInputStream(socket.getInputStream());
                output = socket.getOutputStream();
                handshake();
                connected = true;
                readLoop();
            } catch (Exception ignored) {
                close();
            }
        }).start();
    }

    boolean isConnected() {
        return connected && socket != null && socket.isConnected() && !socket.isClosed();
    }

    synchronized boolean send(String text) {
        if (!isConnected()) return false;
        try {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x81);
            if (payload.length <= 125) {
                frame.write(0x80 | payload.length);
            } else if (payload.length <= 65535) {
                frame.write(0x80 | 126);
                frame.write((payload.length >> 8) & 0xff);
                frame.write(payload.length & 0xff);
            } else {
                frame.write(0x80 | 127);
                frame.write(ByteBuffer.allocate(8).putLong(payload.length).array());
            }

            byte[] mask = new byte[4];
            random.nextBytes(mask);
            frame.write(mask);
            for (int i = 0; i < payload.length; i++) {
                frame.write(payload[i] ^ mask[i % 4]);
            }
            output.write(frame.toByteArray());
            output.flush();
            return true;
        } catch (Exception error) {
            close();
            return false;
        }
    }

    void close() {
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        if (listener != null) listener.onClosed();
    }

    private void handshake() throws Exception {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");

        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + host + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        output.write(request.getBytes(StandardCharsets.US_ASCII));
        output.flush();

        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            headers.write(b);
            String text = headers.toString("US-ASCII");
            if (text.contains("\r\n\r\n")) {
                if (!text.startsWith("HTTP/1.1 101") && !text.startsWith("HTTP/1.0 101")) {
                    throw new IllegalStateException("WebSocket upgrade failed");
                }
                return;
            }
        }
        throw new IllegalStateException("WebSocket handshake failed");
    }

    private void readLoop() throws Exception {
        while (isConnected()) {
            int first = input.read();
            int second = input.read();
            if (first < 0 || second < 0) break;

            int opcode = first & 0x0f;
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7f;
            if (length == 126) {
                length = (input.read() << 8) | input.read();
            } else if (length == 127) {
                byte[] bytes = readBytes(8);
                length = ByteBuffer.wrap(bytes).getLong();
            }

            byte[] mask = masked ? readBytes(4) : null;
            byte[] payload = readBytes((int) length);
            if (masked && mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }

            if (opcode == 8) break;
            if (opcode == 1 && listener != null) {
                listener.onMessage(new String(payload, StandardCharsets.UTF_8));
            }
        }
        close();
    }

    private byte[] readBytes(int length) throws Exception {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(data, offset, length - offset);
            if (read < 0) throw new IllegalStateException("Socket closed");
            offset += read;
        }
        return data;
    }
}
