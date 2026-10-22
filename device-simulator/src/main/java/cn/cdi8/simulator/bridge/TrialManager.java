package cn.cdi8.simulator.bridge;

import cn.cdi8.simulator.runtime.ComponentModel;
import cn.cdi8.simulator.runtime.ComponentRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Isolated SCXML trials used by the workbench model editor. */
final class TrialManager implements AutoCloseable {
    private final Path root;
    private final Map<String,Trial> trials=new ConcurrentHashMap<>();
    TrialManager(Path root) throws Exception { Class.forName("org.sqlite.JDBC");this.root=root.toAbsolutePath();Files.createDirectories(this.root); }
    JsonNode validate(String xml) {
        ObjectNode result=ComponentModel.JSON.createObjectNode();ArrayNode diagnostics=result.putArray("diagnostics");
        try { ComponentModel model=new ComponentModel(xml);result.put("valid",true).set("capabilities",capabilities(model)); }
        catch(Exception ex){result.put("valid",false);diagnostics.addObject().put("severity","error").put("message",message(ex));}
        return result;
    }
    synchronized JsonNode create(String xml) throws Exception {
        ComponentModel model=new ComponentModel(xml);String id=UUID.randomUUID().toString().replace("-","");Path dir=root.resolve(id);Files.createDirectories(dir);
        try {Files.writeString(dir.resolve("model.scxml"),xml);Trial trial=new Trial(xml,model,new ComponentRuntime(model,dir.resolve("state.sqlite3")));trials.put(id,trial);return view(id);}catch(Exception ex){delete(dir);throw ex;}
    }
    synchronized JsonNode view(String id){Trial t=trial(id);ObjectNode v=ComponentModel.JSON.createObjectNode().put("id",id).put("xml",t.xml);JsonNode snap=t.runtime.snapshot();v.set("snapshot",snap);v.set("history",t.runtime.history(100));v.set("capabilities",capabilities(t.model));ArrayNode available=v.putArray("availableActions");String leaf=snap.path("inner_state").asText();t.model.metadata.path("actions").fieldNames().forEachRemaining(a->{if(t.model.action(a).path("requests").has(leaf))available.add(a);});return v;}
    synchronized JsonNode command(String id,JsonNode body){Trial t=trial(id);if(body==null||!body.isObject())throw new IllegalArgumentException("body 必须是 JSON 对象");ObjectNode request=body.deepCopy();request.put("command_id",UUID.randomUUID().toString().replace("-", ""));t.runtime.execute(request);return view(id);}
    synchronized JsonNode simulate(String id,JsonNode body){trial(id).runtime.simulate(body);return view(id);}
    synchronized JsonNode event(String id,JsonNode body){trial(id).runtime.reportEvent(body);return view(id);}
    private Trial trial(String id){if(id==null||!id.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("非法试运行编号");Trial t=trials.get(id);if(t==null)throw new NoSuchElementException("unknown trial: "+id);return t;}
    static ObjectNode capabilities(ComponentModel model){ObjectNode r=ComponentModel.JSON.createObjectNode();ObjectNode actions=r.putObject("actions");model.metadata.path("actions").fields().forEachRemaining(e->{ObjectNode a=actions.putObject(e.getKey());a.put("label",e.getValue().path("label").asText(e.getKey()));a.set("requests",e.getValue().path("requests"));});ObjectNode confirmations=r.putObject("confirmations");model.metadata.path("confirmations").fields().forEachRemaining(e->{ObjectNode c=confirmations.putObject(e.getKey());c.put("label",e.getValue().path("label").asText(e.getKey()));c.set("source_outer",e.getValue().path("source_outer"));c.set("observation",e.getValue().path("observation"));});return r;}
    private static String message(Throwable e){Throwable c=e;while(c.getCause()!=null)c=c.getCause();return c.getMessage()==null?c.toString():c.getMessage();}
    private static void delete(Path p){try(var s=Files.walk(p)){s.sorted(Comparator.reverseOrder()).forEach(x->{try{Files.deleteIfExists(x);}catch(Exception ignored){}});}catch(Exception ignored){}}
    @Override public void close(){trials.values().forEach(t->{try{t.runtime.close();}catch(Exception ignored){}});trials.clear();}
    private record Trial(String xml,ComponentModel model,ComponentRuntime runtime){}
}
