package cn.cdi8.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Facility-only smoke test.  It starts the real facility jar and its simulator
 * child, without starting Workbench or Flowable, and verifies the observation
 * boundary before reading facts.
 */
class FacilityObservationIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).build();
    private static Path directory;
    private static Process facility;
    private static String base;

    @BeforeAll
    static void startFacility() throws Exception {
        directory = Files.createTempDirectory("cdi8-facility-observation-");
        Path facilityJar = Path.of("facility-server/target/facility-server-0.1.0.jar").toAbsolutePath();
        Path simulatorJar = Path.of("device-simulator/target/device-simulator-0.1.0.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(facilityJar), "先执行 facility-e2e 验证，使设施 JAR 可用");
        assertTrue(Files.isRegularFile(simulatorJar), "先执行 facility-e2e 验证，使模拟器 JAR 可用");
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path log = directory.resolve("facility.log");
        facility = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", facilityJar.toString(),
            "--server.port=" + port,
            "--control.simulation.enabled=true",
            "--control.simulation.jar=" + simulatorJar,
            "--control.simulation.directory=" + directory.resolve("simulator"),
            "--control.facility-store=" + directory.resolve("supervision"),
            "--logging.level.root=ERROR")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        base = "http://127.0.0.1:" + port + "/api/facility";
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (true) {
            if (!facility.isAlive())
                fail("设施后端启动失败：" + Files.readString(log));
            if (System.nanoTime() > deadline)
                fail("设施后端启动超时：" + Files.readString(log));
            try {
                if (request("GET", "/health", null).path("status").asText().equals("up")
                    && request("GET", "/connection", null).path("connected").asBoolean()) break;
            } catch (Exception ignored) {
                // The embedded server and simulator need a short warm-up.
            }
            Thread.sleep(100);
        }
    }

    @AfterAll
    static void stopFacility() throws Exception {
        if (facility == null) return;
        var children = facility.descendants().toList();
        facility.destroy();
        if (!facility.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) facility.destroyForcibly();
        for (var child : children) if (child.isAlive()) child.destroyForcibly();
        System.out.println("Facility observation test logs: " + directory);
    }

    @Test
    void observesIndependentDeviceSnapshotsBeforeFacts() throws Exception {
        JsonNode environment = request("GET", "/environment", null);
        assertEquals("ready", environment.path("status").asText());
        assertEquals(14, environment.path("devices").size());

        JsonNode catalog = request("GET", "/devices", null);
        assertEquals(15, catalog.size(), "14 个组件设备加上自动创建的 context_01");
        Set<String> sourceIds = new HashSet<>();
        for (JsonNode item : catalog) {
            assertTrue(item.path("connected").asBoolean(), item.toPrettyString());
            JsonNode snapshot = item.path("snapshot");
            assertTrue(snapshot.isObject(), item.toPrettyString());
            assertTrue(Set.of("component", "condition-source").contains(snapshot.path("snapshot_kind").asText()));
            assertFalse(snapshot.path("source_id").asText().isBlank());
            assertTrue(snapshot.path("state_revision").asLong() >= 0);
            assertTrue(sourceIds.add(snapshot.path("source_id").asText()), "不同设备不能共享 source_id");
            assertTrue(snapshot.path("active_command_id").isNull());
            assertTrue(snapshot.path("active_action").isNull());
        }

        JsonNode firstEvent = firstEvent();
        assertEquals("snapshot", firstEvent.path("type").asText());
        assertFalse(firstEvent.path("generation_id").asText().isBlank());
        assertTrue(firstEvent.path("snapshot").has("source_id"));

        JsonNode seedBefore = request("GET", "/devices/seed_01", null);
        JsonNode shgBefore = request("GET", "/devices/shg_01", null);
        long seedRevision = seedBefore.path("state_revision").asLong();
        String oldEnvironment = environment.path("id").asText();

        JsonNode accepted = request("POST", "/devices/seed_01/commands", JSON.createObjectNode()
            .put("command_id", "observation-smoke")
            .put("action", "power_on_check")
            .set("parameters", JSON.createObjectNode()));
        assertEquals("accepted", accepted.path("status").asText(), accepted.toPrettyString());

        JsonNode executing = eventually(() -> request("GET", "/devices/seed_01", null),
            value -> "observation-smoke".equals(value.path("active_command_id").asText()),
            "没有观察到 seed_01 进入 executing");
        assertTrue(executing.path("state_revision").asLong() > seedRevision);
        assertEquals("executing", executing.path("task_state").asText());

        JsonNode completed = eventually(() -> request("GET", "/devices/seed_01", null),
            value -> "observation-smoke".equals(value.path("last_result").path("command_id").asText()),
            "没有观察到 seed_01 的完成快照");
        assertTrue(completed.path("active_command_id").isNull());
        assertTrue(completed.path("state_revision").asLong() > executing.path("state_revision").asLong());
        eventually(() -> request("GET", "/devices/seed_01/commands/observation-smoke", null),
            value -> "succeeded".equals(value.path("status").asText()),
            "设施命令日志没有取得设备终态");
        JsonNode shgAfter = request("GET", "/devices/shg_01", null);
        assertEquals(shgBefore.path("source_id"), shgAfter.path("source_id"));
        assertEquals(shgBefore.path("state_revision").asLong(), shgAfter.path("state_revision").asLong());

        JsonNode facts = request("GET", "/facts?refresh=true", null);
        assertFalse(facts.has("constraints"), "事实接口不能泄漏监督约束");

        var replacementRequest = JSON.createObjectNode().put("autoComplete", true);
        replacementRequest.set("conditions", JSON.createObjectNode());
        var replacementDevices = JSON.createArrayNode();
        replacementDevices.add(JSON.createObjectNode()
            .put("id", "seed_01")
            .put("deviceBindingKey", "seed")
            .put("model", "seed-source-state-machine.scxml")
            .put("xml", Files.readString(Path.of("examples/gxlf/components/seed-source-state-machine.scxml")))
            .put("revision", "observation-smoke-2")
            .put("autoComplete", true)
            .put("delayMs", 20));
        replacementRequest.set("devices", replacementDevices);
        JsonNode replacement = request("POST", "/environment", replacementRequest);
        assertNotEquals(oldEnvironment, replacement.path("id").asText());
        JsonNode newSeed = eventually(() -> request("GET", "/devices/seed_01", null),
            value -> !value.path("source_id").asText().equals(seedBefore.path("source_id").asText())
                && value.path("state_revision").asLong() == 0,
            "环境重建后没有取得新的 seed_01 快照代次");
        assertNotEquals(seedBefore.path("source_id"), newSeed.path("source_id"));
        assertEquals(0, newSeed.path("state_revision").asLong());
        assertEquals(2, request("GET", "/devices", null).size(), "重建环境只包含 seed_01 和 context_01");
    }

    private static JsonNode firstEvent() throws Exception {
        HttpRequest http = HttpRequest.newBuilder(URI.create(base + "/events"))
            .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<InputStream> response = HTTP.send(http, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        try (InputStream input = response.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            assertNotNull(line, "设施事件流没有启动快照");
            return JSON.readTree(line);
        }
    }

    private static JsonNode eventually(CheckedSupplier<JsonNode> read, Predicate<JsonNode> condition,
                                       String message) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode value = null;
        while (System.nanoTime() < deadline) {
            value = read.get();
            if (condition.test(value)) return value;
            Thread.sleep(100);
        }
        fail(message + "\n最后快照：" + (value == null ? "null" : value.toPrettyString()));
        return value;
    }

    private static JsonNode request(String method, String path, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(15));
        HttpRequest request = body == null
            ? builder.GET().build()
            : builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300)
            fail(method + " " + path + " 返回 " + response.statusCode() + ": " + response.body());
        return JSON.readTree(response.body());
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }
}
