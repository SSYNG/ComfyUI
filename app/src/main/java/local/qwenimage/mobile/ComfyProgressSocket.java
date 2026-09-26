package local.qwenimage.mobile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/** Small RFC 6455 text-event reader. Binary preview frames are discarded. */
final class ComfyProgressSocket implements AutoCloseable {
    interface Listener {
        void onEvent(JSONObject event);
        void onDisconnect();
    }

    private static final String WEBSOCKET_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private final Socket socket;
    private final Listener listener;
    private final Thread readerThread;
    private volatile boolean closed;

    private ComfyProgressSocket(Socket socket, Listener listener) {
        this.socket = socket;
        this.listener = listener;
        this.readerThread = new Thread(this::readLoop, "comfy-progress");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    static ComfyProgressSocket connect(String baseUrl, String clientId, Listener listener) throws Exception {
        URL url = new URL(baseUrl);
        boolean tls = "https".equalsIgnoreCase(url.getProtocol());
        int port = url.getPort() < 0 ? (tls ? 443 : 80) : url.getPort();
        Socket socket = tls ? javax.net.ssl.SSLSocketFactory.getDefault().createSocket() : new Socket();
        try {
            socket.connect(new InetSocketAddress(url.getHost(), port), 8000);
            socket.setSoTimeout(8000);
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String key = Base64.getEncoder().encodeToString(nonce);
            String path = "/ws?clientId=" + java.net.URLEncoder.encode(clientId, "UTF-8");
            String host = url.getHost() + (url.getPort() < 0 ? "" : ":" + port);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String headers = readHeaders(socket.getInputStream());
            if (!headers.startsWith("HTTP/1.1 101") && !headers.startsWith("HTTP/1.0 101")) {
                throw new IOException("WebSocket 握手失败：" + headers.split("\r\n", 2)[0]);
            }
            String expected = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_MAGIC).getBytes(StandardCharsets.US_ASCII)));
            if (!headers.toLowerCase(Locale.ROOT).contains("sec-websocket-accept: " + expected.toLowerCase(Locale.ROOT))) {
                throw new IOException("WebSocket 响应校验失败");
            }
            socket.setSoTimeout(30000);
            return new ComfyProgressSocket(socket, listener);
        } catch (Exception e) {
            socket.close();
            throw e;
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int match = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (buffer.size() < 16384) {
            int value = in.read();
            if (value < 0) throw new IOException("WebSocket 连接中断");
            buffer.write(value);
            match = value == end[match] ? match + 1 : (value == '\r' ? 1 : 0);
            if (match == 4) return buffer.toString("US-ASCII");
        }
        throw new IOException("WebSocket 响应头过长");
    }

    private void readLoop() {
        ByteArrayOutputStream textParts = null;
        try {
            InputStream in = socket.getInputStream();
            while (!closed) {
                int first;
                try { first = in.read(); }
                catch (SocketTimeoutException timeout) { continue; }
                if (first < 0) break;
                int second = in.read();
                if (second < 0) break;
                boolean fin = (first & 0x80) != 0;
                int opcode = first & 0x0f;
                boolean masked = (second & 0x80) != 0;
                long size = second & 0x7f;
                if (size == 126) size = ((long) readByte(in) << 8) | readByte(in);
                else if (size == 127) {
                    size = 0;
                    for (int i = 0; i < 8; i++) size = (size << 8) | readByte(in);
                }
                if (size < 0 || size > 100L * 1024 * 1024) throw new IOException("WebSocket 帧过大");
                byte[] mask = masked ? readExact(in, 4) : null;
                if (opcode == 8) break;
                boolean keep = opcode == 9 || opcode == 1 || (opcode == 0 && textParts != null);
                if (!keep || size > 1024 * 1024) {
                    skip(in, size);
                    if (opcode == 1 || opcode == 0) textParts = null;
                    continue;
                }
                byte[] payload = readExact(in, (int) size);
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                if (opcode == 9) { sendPong(payload); continue; }
                if (opcode == 1) textParts = new ByteArrayOutputStream();
                if (textParts != null) {
                    textParts.write(payload);
                    if (fin) {
                        try { listener.onEvent(new JSONObject(textParts.toString("UTF-8"))); }
                        catch (Exception ignored) { /* Unknown event; history polling remains active. */ }
                        textParts = null;
                    }
                }
            }
        } catch (Exception ignored) {
            // Result polling continues when the progress channel fails.
        } finally {
            if (!closed) listener.onDisconnect();
            close();
        }
    }

    private synchronized void sendPong(byte[] payload) throws IOException {
        if (closed) return;
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        OutputStream out = socket.getOutputStream();
        out.write(0x8a);
        out.write(0x80 | payload.length);
        out.write(mask);
        for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i % 4]);
        out.flush();
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new IOException("WebSocket 连接中断");
        return value;
    }

    private static byte[] readExact(InputStream in, int length) throws IOException {
        byte[] result = new byte[length];
        int at = 0;
        while (at < length) {
            int count = in.read(result, at, length - at);
            if (count < 0) throw new IOException("WebSocket 连接中断");
            at += count;
        }
        return result;
    }

    private static void skip(InputStream in, long length) throws IOException {
        byte[] buffer = new byte[8192];
        while (length > 0) {
            int count = in.read(buffer, 0, (int) Math.min(buffer.length, length));
            if (count < 0) throw new IOException("WebSocket 连接中断");
            length -= count;
        }
    }

    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
