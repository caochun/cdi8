package cn.cdi8.workbench.integration.tango;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Command transport and a single live subscription to the PyTango bridge. */
@Component
public class TangoGateway {
    public record Notice(JsonNode payload) {}
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final String base;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ExecutorService reader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tango-events"); t.setDaemon(true); return t;
    });
    private volatile boolean running = true;
    private volatile boolean connected;
    private volatile long lastEvent;
    private volatile InputStream stream;
    private volatile String error = "尚未连接 Tango 适配服务";
    private final Map<String, JsonNode> snapshots = new ConcurrentHashMap<>();

    public TangoGateway(ObjectMapper json, ApplicationEventPublisher events,
                        @Value("${control.tango-url}") String base,
                        @Value("${control.events-enabled:true}") boolean enabled) {
        this.json = json; this.events = events; this.base = base; this.enabled = enabled;
    }

    public JsonNode request(String method, String path, Object body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(path.equals("/lab/environment") ? 60 : 5));
            if (body == null) builder.GET();
            else builder.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode value = json.readTree(response.body());
            if (response.statusCode() >= 400) throw new GatewayException(response.statusCode(), value.path("error").asText(response.body()));
            return value;
        } catch (GatewayException ex) { throw ex; }
        catch (Exception ex) { throw new GatewayException(503, "Tango 适配服务通信失败：" + ex.getMessage()); }
    }

    public JsonNode catalog() { return request("GET", "/devices", null); }
    public JsonNode snapshot(String id) { return request("GET", "/devices/" + id, null); }
    public JsonNode execute(String device, String id, String action, Object parameters) {
        return request("POST", "/devices/" + device + "/commands",
            Map.of("command_id", id, "action", action, "parameters", parameters));
    }
    public JsonNode result(String device, String id) { return request("GET", "/devices/" + device + "/commands/" + id, null); }
    public JsonNode setConditions(String device, Map<String, Boolean> values) {
        return request("POST", "/devices/" + device + "/conditions", values);
    }
    public void reconnect() { connected=false; error="模拟环境切换中"; snapshots.clear(); closeStream(); }
    public boolean connected() { return connected; }
    public JsonNode cached(String id) { return snapshots.get(id); }
    public Map<String,Object> status() { return Map.of("connected", connected, "error", error); }

    @EventListener(ApplicationReadyEvent.class)
    public void start() { if (enabled) reader.submit(this::listen); }

    private void listen() {
        while (running) {
            try {
                var request = HttpRequest.newBuilder(URI.create(base + "/events")).GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                stream = response.body();
                if (response.statusCode() != 200) throw new IOException("event stream HTTP " + response.statusCode());
                lastEvent = System.currentTimeMillis(); connected = false; snapshots.clear();
                try (var lines = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while (running && (line = lines.readLine()) != null) {
                        lastEvent = System.currentTimeMillis();
                        JsonNode value = json.readTree(line);
                        if ("ready".equals(value.path("type").asText())) { connected = true; error = ""; }
                        if (value.has("device_id")) {
                            String id = value.get("device_id").asText();
                            if (value.path("connected").asBoolean()) snapshots.put(id, value.path("snapshot"));
                            else snapshots.remove(id);
                            events.publishEvent(new Notice(value));
                        }
                    }
                }
                throw new EOFException("Tango 事件流断开");
            } catch (Exception ex) {
                connected = false; error = Objects.toString(ex.getMessage(), "事件流异常"); snapshots.clear();
                if (running) {
                    try { Thread.sleep(1000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            } finally {
                closeStream();
            }
        }
    }

    @Scheduled(fixedDelay = 3000)
    public void watchdog() {
        if (connected && System.currentTimeMillis() - lastEvent > 15000) {
            connected = false; error = "Tango 事件流心跳超时"; snapshots.clear(); closeStream();
        }
    }
    private void closeStream() { try { if (stream != null) stream.close(); } catch (IOException ignored) {} }
    @PreDestroy public void close() { running = false; connected = false; closeStream(); reader.shutdownNow(); }

    public static class GatewayException extends RuntimeException {
        private final int status;
        public int status() { return status; }
        public GatewayException(int status, String message) { super(message); this.status = status; }
    }
}
