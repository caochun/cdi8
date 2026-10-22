import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Java 17 source launcher. Run from the repository root: java scripts/Workbench.java start|stop|restart|status. */
class Workbench {
    static final Path ROOT=Path.of("").toAbsolutePath().normalize();
    static final Path RUNTIME=ROOT.resolve("data/runtime");
    static final String JAVA=Path.of(System.getProperty("java.home"),"bin","java").toString();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    record Service(String name,String source,String jar,int port,String ready,List<String> arguments) {
        Path pid(){return RUNTIME.resolve(name+".pid");}
        Path executable(){return RUNTIME.resolve(jar);}
        Path log(){return RUNTIME.resolve(name+".log");}
    }
    static final Service BRIDGE=new Service("bridge","device-simulator/target/device-simulator-0.1.0.jar",
        "device-simulator-0.1.0.jar",8766,"/lab/environment",List.of("--bridge","--port","8766",
        "--data-dir",ROOT.resolve("data/java-simulator").toString()));
    static final Service SERVER=new Service("workbench","server/target/workbench-server-0.1.0.jar",
        "workbench-server-0.1.0.jar",8080,"/api/connection",List.of());

    public static void main(String[] args) throws Exception {
        if(args.length!=1||!Set.of("start","stop","restart","status").contains(args[0]))
            throw new IllegalArgumentException("用法：java scripts/Workbench.java start|stop|restart|status");
        if(!Files.exists(ROOT.resolve("pom.xml")))throw new IllegalStateException("请在项目根目录执行");
        Files.createDirectories(RUNTIME);
        try(var channel=FileChannel.open(RUNTIME.resolve("workbench.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("另一个工作台启动/停止操作正在进行");
            switch(args[0]) {
                case "stop" -> stopAll();
                case "restart" -> {stopAll();startAll();}
                case "start" -> startAll();
                case "status" -> status();
            }
        }
    }
    static void startAll() throws Exception {
        List<Process> started=new ArrayList<>();
        try {
            for(Service service:List.of(BRIDGE,SERVER)) {
                if(running(service).isPresent())continue;
                if(!Files.isRegularFile(ROOT.resolve(service.source)))throw new IllegalStateException("缺少构建产物，请先执行 mvn package："+service.source);
                try(var socket=new ServerSocket()) {socket.setReuseAddress(true);socket.bind(new InetSocketAddress("127.0.0.1",service.port));}
                catch(IOException ex){throw new IllegalStateException("端口 "+service.port+" 已被其他服务占用，请先停止旧服务",ex);}
            }
            for(Service service:List.of(BRIDGE,SERVER)) {
                if(running(service).isPresent()){System.out.println(service.name+" 已运行");continue;}
                Files.copy(ROOT.resolve(service.source),service.executable(),StandardCopyOption.REPLACE_EXISTING);
                List<String> command=new ArrayList<>(List.of(JAVA,"-jar",service.executable().toString()));command.addAll(service.arguments);
                Process process=new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(service.log().toFile())).start();
                started.add(process);Files.writeString(service.pid(),Long.toString(process.pid()));
                System.out.println("启动 "+service.name+"，PID "+process.pid()+"；日志："+service.log());
                long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
                while(!ready(service)) {
                    if(!process.isAlive())throw new IllegalStateException(service.name+" 启动失败，请查看 "+service.log());
                    if(System.nanoTime()>deadline)throw new IllegalStateException(service.name+" 就绪超时，请查看 "+service.log());
                    Thread.sleep(250);
                }
            }
            System.out.println("工作台：http://127.0.0.1:8080/；Java bridge：http://127.0.0.1:8766");
        }catch(Exception ex){for(Process process:started)terminate(process.toHandle());throw ex;}
    }
    static boolean ready(Service service) {
        try {
            var response=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+service.port+service.ready))
                .timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
            return response.statusCode()==200&&(service!=BRIDGE||response.body().contains("\"runtime\":\"java\""));
        }catch(Exception ex){return false;}
    }
    static Optional<ProcessHandle> running(Service service) throws IOException {
        if(!Files.exists(service.pid()))return Optional.empty();
        try {
            return ProcessHandle.of(Long.parseLong(Files.readString(service.pid()).trim())).filter(ProcessHandle::isAlive)
                .filter(p->Arrays.asList(p.info().arguments().orElse(new String[0])).contains(service.executable().toString()));
        }catch(NumberFormatException ex){return Optional.empty();}
    }
    static void stopAll() throws Exception {
        for(Service service:List.of(SERVER,BRIDGE)) {
            var process=running(service);
            if(process.isPresent()) {
                List<ProcessHandle> children=process.get().descendants().toList();
                terminate(process.get());for(ProcessHandle child:children)if(child.isAlive())terminate(child);
                System.out.println("已停止 "+service.name);
            }
            Files.deleteIfExists(service.pid());
        }
    }
    static void terminate(ProcessHandle process) {
        process.destroy();
        try{process.onExit().get(8,TimeUnit.SECONDS);}catch(Exception ex){process.destroyForcibly();}
    }
    static void status() throws Exception {
        for(Service service:List.of(BRIDGE,SERVER)) {
            var process=running(service);
            System.out.println(service.name+"："+(process.isPresent()?"PID "+process.get().pid()+(ready(service)?"，已就绪":"，未就绪"):"未启动")+"；日志："+service.log());
        }
    }
}
