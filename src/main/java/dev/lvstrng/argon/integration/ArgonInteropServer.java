package dev.lvstrng.argon.integration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import dev.lvstrng.argon.Argon;
import dev.lvstrng.argon.module.Category;
import dev.lvstrng.argon.module.Module;
import dev.lvstrng.argon.module.setting.*;
import net.minecraft.client.MinecraftClient;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ArgonInteropServer — Phase 2 of the LiquidBounce GUI port.
 *
 * Hosts a local HTTP server that:
 *  1. Serves the compiled LiquidBounce Svelte theme (static files from resources)
 *  2. Exposes a JSON REST API matching LiquidBounce's /api/v1/client/* contract
 *     so the Svelte frontend can read and write Argon's modules/settings
 *
 * LiquidBounce Svelte expected endpoints (minimum required for ClickGUI):
 *   GET  /api/v1/client/info          -> client metadata
 *   GET  /api/v1/client/modules       -> all modules as JSON array
 *   POST /api/v1/client/modules/toggle -> enable/disable a module
 *   GET  /api/v1/client/modules/settings?name=X -> module settings as JSON
 *   PUT  /api/v1/client/modules/settings?name=X -> update module settings
 *   GET  /api/v1/client/window        -> window/screen dimensions
 */
public class ArgonInteropServer {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static int PORT = 0; // 0 = OS picks a free port

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;
    private static ArgonInteropServer INSTANCE;

    public static ArgonInteropServer getInstance() {
        if (INSTANCE == null) INSTANCE = new ArgonInteropServer();
        return INSTANCE;
    }

    public void start() throws IOException {
        // Bind on all interfaces (0.0.0.0) so both IPv4 and IPv6 loopback are served.
        // Windows may resolve "localhost" to ::1 (IPv6), which would be refused if we
        // only bound to 127.0.0.1. Binding on the wildcard avoids that mismatch entirely.
        serverSocket = new ServerSocket(PORT, 50, java.net.InetAddress.getByName("0.0.0.0"));
        PORT = serverSocket.getLocalPort();
        running = true;
        System.out.println("[ArgonInterop] Server bound to: 0.0.0.0:" + PORT);
        executor = java.util.concurrent.Executors.newCachedThreadPool();
        new Thread(this::acceptLoop, "ArgonInterop-Acceptor").start();
        System.out.println("[ArgonInterop] Accept loop started on dedicated thread.");
        System.out.println("[ArgonInterop] HTTP server started on port " + PORT);
    }

    public void stop() {
        running = false;
        try { serverSocket.close(); } catch (IOException ignored) {}
        if (executor != null) executor.shutdownNow();
    }

    public String getUrl() {
        // Always use the numeric IPv4 loopback to avoid OS-level DNS resolution
        // sending MCEF/Chromium to ::1 (IPv6) when the socket is on 127.0.0.1.
        return "http://127.0.0.1:" + PORT;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accept loop
    // ─────────────────────────────────────────────────────────────────────────

    private void acceptLoop() {
        System.out.println("[ArgonInterop] Accept loop entering while(running) block...");
        while (running) {
            try {
                if (serverSocket == null || serverSocket.isClosed()) {
                    System.err.println("[ArgonInterop] ServerSocket is null or closed, exiting acceptLoop.");
                    break;
                }
                Socket client = serverSocket.accept();
                System.out.println("[ArgonInterop] Accepted connection from: " + client.getRemoteSocketAddress());
                if (executor == null || executor.isShutdown()) {
                    executor = Executors.newCachedThreadPool(); // safety fallback
                }
                executor.submit(() -> handle(client));
            } catch (IOException e) {
                if (running) {
                    System.err.println("[ArgonInterop] Accept error: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
        System.out.println("[ArgonInterop] Accept loop exiting.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Request handler
    // ─────────────────────────────────────────────────────────────────────────

    private void handle(Socket client) {
        System.out.println("[ArgonInterop] Handling connection from: " + client.getRemoteSocketAddress());
        try (client;
             InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            // Parse request line
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0];
            String fullPath = parts[1];
            System.out.println("[ArgonInterop] Request: " + method + " " + fullPath);

            // Read headers
            int contentLength = 0;
            boolean isWebSocket = false;
            String wsKey = "";
            String wsProtocol = "";
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                String lowerLine = line.toLowerCase();
                if (lowerLine.startsWith("content-length:"))
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                if (lowerLine.startsWith("upgrade: websocket"))
                    isWebSocket = true;
                if (lowerLine.startsWith("sec-websocket-key:"))
                    wsKey = line.split(":")[1].trim();
                if (lowerLine.startsWith("sec-websocket-protocol:"))
                    wsProtocol = line.split(":")[1].trim();
            }

            if (isWebSocket && !wsKey.isEmpty()) {
                System.out.println("[ArgonInterop] Upgrading to dummy WebSocket...");
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
                byte[] hashed = md.digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.UTF_8));
                String accept = java.util.Base64.getEncoder().encodeToString(hashed);
                
                String header = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + accept + "\r\n";
                
                if (!wsProtocol.isEmpty()) {
                    header += "Sec-WebSocket-Protocol: " + wsProtocol + "\r\n";
                }
                header += "\r\n";
                
                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.flush();
                
                // Keep the socket alive so the frontend thinks it's connected
                try {
                    while (in.read() != -1) {
                        // ignore incoming websocket frames
                    }
                } catch (Exception ignored) {}
                System.out.println("[ArgonInterop] Dummy WebSocket closed.");
                return;
            }

            // Read body
            String body = "";
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                reader.read(buf, 0, contentLength);
                body = new String(buf);
            }

            // Split path and query
            String path = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf('?')) : fullPath;
            String query = fullPath.contains("?") ? fullPath.substring(fullPath.indexOf('?') + 1) : "";

            // Handle static files directly (they write their own HTTP response)
            if ("GET".equals(method) && path.startsWith("/resource/liquidbounce")) {
                serveStaticFile(path, out);
                return;
            }

            // Handle root redirect for safety
            if (path.equals("/") || path.isEmpty()) {
                String header = "HTTP/1.1 302 Found\r\n" +
                        "Location: /resource/liquidbounce/index.html\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                return;
            }

            // Handle OPTIONS pre-flight
            if ("OPTIONS".equals(method)) {
                String header = "HTTP/1.1 204 No Content\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: *\r\n\r\n";
                out.write(header.getBytes(StandardCharsets.UTF_8));
                return;
            }

            // Route to JSON API handlers
            String response = route(method, path, query, body);
            if (response != null && response.length() > 500) {
                 System.out.println("[ArgonInterop] Response: " + response.substring(0, 100) + "... (total " + response.length() + ")");
            } else {
                 System.out.println("[ArgonInterop] Response: " + response);
            }

            // Write HTTP response
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: *\r\n" +
                    "Content-Length: " + responseBytes.length + "\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(responseBytes);
            out.flush();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Router
    // ─────────────────────────────────────────────────────────────────────────

    private String route(String method, String path, String query, String body) {
        // Handle OPTIONS pre-flight
        if ("OPTIONS".equals(method)) return "{}";

        // ── GET /api/v1/client/info
        System.out.println("[ArgonInterop] Request: " + method + " " + path + (query != null ? "?" + query : ""));

        if ("GET".equals(method) && path.equals("/api/v1/client/info")) {
            return getClientInfo();
        }

        // ── GET /api/v1/client/window
        if ("GET".equals(method) && path.equals("/api/v1/client/window")) {
            return getWindowInfo();
        }

        // ── GET /api/v1/client/modules
        if ("GET".equals(method) && path.equals("/api/v1/client/modules")) {
            return getModules();
        }

        // ── POST/PUT/DELETE /api/v1/client/modules/toggle
        if (path.equals("/api/v1/client/modules/toggle")) {
            System.out.println("[ArgonInterop] Toggle body: " + body);
            return toggleModule(body, method);
        }

        // ── GET /api/v1/client/modules/settings?name=X
        if ("GET".equals(method) && path.equals("/api/v1/client/modules/settings")) {
            String name = getQueryParam(query, "name");
            System.out.println("[ArgonInterop] Get Settings for: " + name);
            return getModuleSettings(name);
        }

        // ── PUT /api/v1/client/modules/settings?name=X
        if ("PUT".equals(method) && path.equals("/api/v1/client/modules/settings")) {
            String name = getQueryParam(query, "name");
            System.out.println("[ArgonInterop] Put Settings for: " + name + " body: " + body);
            return putModuleSettings(name, body);
        }

        // ── GET /api/v1/client/module/:name
        if ("GET".equals(method) && path.startsWith("/api/v1/client/module/")) {
            String name = path.substring("/api/v1/client/module/".length());
            return getSingleModule(name);
        }

        // ── GET /api/v1/client/localStorage/all
        if ("GET".equals(method) && path.equals("/api/v1/client/localStorage/all")) {
            return "{}";
        }

        return "{}";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API Handlers — these mirror LiquidBounce's API shape exactly
    // ─────────────────────────────────────────────────────────────────────────

    /** GET /api/v1/client/info */
    private String getClientInfo() {
        JsonObject obj = new JsonObject();
        obj.addProperty("clientName", "Argon");
        obj.addProperty("clientVersion", Argon.INSTANCE.getVersion());
        obj.addProperty("gameVersion", "1.21.11");
        obj.addProperty("development", false);
        obj.addProperty("inGame", MinecraftClient.getInstance().world != null);
        return GSON.toJson(obj);
    }

    /** GET /api/v1/client/window */
    private String getWindowInfo() {
        MinecraftClient mc = MinecraftClient.getInstance();
        JsonObject obj = new JsonObject();
        if (mc.getWindow() != null) {
            obj.addProperty("width", mc.getWindow().getWidth());
            obj.addProperty("height", mc.getWindow().getHeight());
            obj.addProperty("scaledWidth", mc.getWindow().getScaledWidth());
            obj.addProperty("scaledHeight", mc.getWindow().getScaledHeight());
            // Hardcode scaleFactor to 2.0 to prevent Svelte from double-zooming,
            // as we are already rendering the browser at the scaled dimensions.
            obj.addProperty("scaleFactor", 2.0);
        }
        return GSON.toJson(obj);
    }

    /** GET /api/v1/client/modules — returns all Argon modules in LiquidBounce schema */
    private String getModules() {
        JsonArray arr = new JsonArray();
        for (Module m : Argon.INSTANCE.getModuleManager().getModules()) {
            JsonObject json = moduleToJson(m);
            System.out.println("[ArgonInterop] Sending module: " + m.getName().toString() + " (" + m.getCategory().name.toString() + ") enabled=" + m.isEnabled());
            arr.add(json);
        }
        return GSON.toJson(arr);
    }

    /** GET /api/v1/client/module/:name */
    private String getSingleModule(String name) {
        for (Module m : Argon.INSTANCE.getModuleManager().getModules()) {
            if (m.getName().toString().equalsIgnoreCase(name)) {
                return GSON.toJson(moduleToJson(m));
            }
        }
        return "{}";
    }

    private String toggleModule(String body, String method) {
        try {
            JsonObject req = GSON.fromJson(body, JsonObject.class);
            String name = req.get("name").getAsString();
            boolean desired = req.get("enabled").getAsBoolean();
            
            Module m = findModule(name);
            if (m != null) {
                // Run on Minecraft main thread to be safe
                MinecraftClient.getInstance().execute(() -> {
                    m.setEnabled(desired);
                    System.out.println("[ArgonInterop] Module " + m.getName().toString() + " is now " + m.isEnabled());
                });
                
                // Return module in LB schema immediately with the desired state
                JsonObject obj = new JsonObject();
                obj.addProperty("name", m.getName().toString());
                obj.addProperty("enabled", desired);
                obj.addProperty("state", desired);
                obj.addProperty("active", desired);
                obj.addProperty("category", categoryTag(m.getCategory()));
                obj.addProperty("description", m.getDescription().toString());
                
                JsonObject kb = new JsonObject();
                kb.addProperty("boundKey", "key.keyboard.unknown");
                kb.add("modifiers", new JsonArray());
                obj.add("keyBind", kb);
                
                return GSON.toJson(obj);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "{}";
    }

    /**
     * GET /api/v1/client/modules/settings?name=X
     * Serializes Argon settings into LiquidBounce's configurable value format.
     */
    private String getModuleSettings(String name) {
        Module module = findModule(name);
        if (module == null) return "[]";

        JsonArray arr = new JsonArray();
        for (Setting<?> s : module.getSettings()) {
            JsonObject sObj = settingToJson(s);
            if (sObj != null) arr.add(sObj);
        }
        return GSON.toJson(arr);
    }

    /**
     * PUT /api/v1/client/modules/settings?name=X
     * Body is a JSON array of { "name": "...", "value": ... }
     */
    private String putModuleSettings(String name, String body) {
        Module module = findModule(name);
        if (module == null) return "{}";

        try {
            JsonElement json = GSON.fromJson(body, JsonElement.class);
            if (json.isJsonArray()) {
                JsonArray updates = json.getAsJsonArray();
                for (var el : updates) {
                    JsonObject update = el.getAsJsonObject();
                    String settingName = update.get("name").getAsString();
                    for (Setting<?> s : module.getSettings()) {
                        if (s.getName().toString().equalsIgnoreCase(settingName)) {
                            applySettingValue(s, update.get("value"));
                            break;
                        }
                    }
                }
            } else if (json.isJsonObject()) {
                JsonObject update = json.getAsJsonObject();
                String settingName = update.get("name").getAsString();
                for (Setting<?> s : module.getSettings()) {
                    if (s.getName().toString().equalsIgnoreCase(settingName)) {
                        applySettingValue(s, update.get("value"));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "{}";
        }
        return "{}";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Serialization helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Converts an Argon Module to a JSON object matching LiquidBounce's schema */
    private JsonObject moduleToJson(Module m) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", m.getName().toString());
        obj.addProperty("category", categoryTag(m.getCategory()));
        obj.addProperty("enabled", m.isEnabled());
        obj.addProperty("state", m.isEnabled());
        obj.addProperty("active", m.isEnabled());
        obj.addProperty("description", m.getDescription() != null ? m.getDescription().toString() : "");
        obj.addProperty("tag", "");
        obj.addProperty("hidden", false);
        obj.add("settings", new JsonArray());
        
        JsonObject keyBind = new JsonObject();
        keyBind.addProperty("boundKey", "key.keyboard.unknown");
        keyBind.add("modifiers", new JsonArray());
        obj.add("keyBind", keyBind);
        return obj;
    }

    /** Converts an Argon Setting to LiquidBounce's "configurable value" JSON shape */
    private JsonObject settingToJson(Setting<?> s) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", s.getName().toString());
        if (s.getDescription() != null) obj.addProperty("description", s.getDescription().toString());

        if (s instanceof BooleanSetting bs) {
            obj.addProperty("type", "bool");
            obj.addProperty("value", bs.getValue());
        } else if (s instanceof NumberSetting ns) {
            obj.addProperty("type", "number");
            obj.addProperty("value", ns.getValue());
            obj.addProperty("min", ns.getMin());
            obj.addProperty("max", ns.getMax());
            obj.addProperty("step", (ns.getValue() == (int)ns.getValue()) ? 1 : 0.1);
        } else if (s instanceof ModeSetting<?> ms) {
            obj.addProperty("type", "mode");
            obj.addProperty("value", ms.getMode().toString());
            JsonArray choices = new JsonArray();
            var mode = ms.getMode();
            if (mode != null) {
                for (var constant : mode.getClass().getEnumConstants()) {
                    choices.add(constant.toString());
                }
            }
            obj.add("values", choices);
        } else if (s instanceof KeybindSetting ks) {
            obj.addProperty("type", "key");
            obj.addProperty("value", ks.getKey());
        } else {
            return null; // skip unknown types
        }
        return obj;
    }

    /** Applies a JSON value element to a Setting */
    @SuppressWarnings("unchecked")
    private void applySettingValue(Setting<?> s, com.google.gson.JsonElement value) {
        if (value == null || value.isJsonNull()) return;
        MinecraftClient.getInstance().execute(() -> {
            if (s instanceof BooleanSetting bs) {
                bs.setValue(value.getAsBoolean());
            } else if (s instanceof NumberSetting ns) {
                ns.setValue(value.getAsDouble());
            } else if (s instanceof KeybindSetting ks) {
                ks.setKey(value.getAsInt());
            } else if (s instanceof ModeSetting ms) {
                // find enum constant by name
                var current = ms.getMode();
                if (current != null) {
                    String desired = value.getAsString();
                    for (var constant : current.getClass().getEnumConstants()) {
                        if (constant.toString().equalsIgnoreCase(desired)) {
                            ms.setMode(constant);
                            break;
                        }
                    }
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private Module findModule(String name) {
        if (name == null) return null;
        String search = name.trim();
        for (Module m : Argon.INSTANCE.getModuleManager().getModules()) {
            String mName = m.getName().toString().trim();
            if (mName.equalsIgnoreCase(search)) return m;
        }
        System.out.println("[ArgonInterop] Failed to find module: '" + name + "'");
        return null;
    }

    private String getQueryParam(String query, String key) {
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key))
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    private String categoryTag(Category cat) {
        return switch (cat) {
            case COMBAT   -> "Combat";
            case MOVEMENT -> "Movement";
            case PLAYER   -> "Player";
            case RENDER   -> "Render";
            case WORLD    -> "World";
            case EXPLOIT  -> "Exploit";
            case MISC     -> "Misc";
            case CLIENT   -> "Client";
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static file serving — Phase 4
    // Serves Svelte dist files from classpath: assets/argon/theme/liquidbounce/
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serves a static file from the classpath.
     * URL pattern: /resource/liquidbounce/{file...}
     * Maps to:     /assets/argon/theme/liquidbounce/{file...}
     */
    private void serveStaticFile(String urlPath, OutputStream out) throws IOException {
        // Strip the /resource/liquidbounce prefix
        String resourcePath = urlPath.replace("/resource/liquidbounce", "");
        if (resourcePath.isEmpty() || resourcePath.equals("/")) resourcePath = "/index.html";

        String classpathLocation = "assets/argon/theme/liquidbounce" + resourcePath;
        System.out.println("[ArgonInterop] Serving resource: " + classpathLocation);
        InputStream stream = ArgonInteropServer.class.getClassLoader().getResourceAsStream(classpathLocation);

        if (stream == null) {
            // 404
            String notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot Found";
            out.write(notFound.getBytes(StandardCharsets.UTF_8));
            return;
        }

        byte[] data = stream.readAllBytes();
        stream.close();

        String contentType = detectContentType(resourcePath);
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: " + data.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    /** Maps file extension to MIME type */
    private String detectContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        if (path.endsWith(".png"))  return "image/png";
        if (path.endsWith(".gif"))  return "image/gif";
        if (path.endsWith(".ico"))  return "image/x-icon";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".woff") || path.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    private String errorJson(String msg) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", msg);
        return GSON.toJson(obj);
    }
}

