package cn.cdi8.control;

import java.time.Duration;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;

/** HTTP front door to the Python Tango gateway; transport is selected there. */
@Component
public class HttpDeviceAdapter implements DeviceAdapter {
    private final RestClient client;
    private final ObjectMapper json;
    public HttpDeviceAdapter(@Value("${control.adapter-url}") String url, ObjectMapper json) {
        this.json=json;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
    @SuppressWarnings("unchecked") private Map<String,Object> get(String path) {
        return client.get().uri(path).retrieve().body(Map.class);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> post(String path, Map<String,Object> data) {
        try {
            byte[] bytes=json.writeValueAsBytes(data);
            return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).contentLength(bytes.length).body(bytes).retrieve().body(Map.class);
        } catch(java.io.IOException ex) { throw new IllegalArgumentException(ex); }
    }
    public Map<String,Object> snapshot(String d) { return get("/devices/"+d); }
    public Map<String,Object> send(String d,String id,String a,Map<String,Object> parameters) { return post("/devices/"+d+"/commands",Map.of("command_id",id,"action",a,"parameters",parameters)); }
    public Map<String,Object> result(String d,String id) { return get("/devices/"+d+"/commands/"+id); }
    public void cancel(String d,String id) { post("/devices/"+d+"/cancel",Map.of("command_id",id)); }
    public void simulate(String d,String id,String outcome) { post("/devices/"+d+"/simulate",Map.of("command_id",id,"outcome",outcome)); }
}
