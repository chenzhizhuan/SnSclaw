package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DshOwnedProcessTreeTest {
    @TempDir Path directory;
    @Test void rootCrashCleansAnObservedChildHoldingTheSdkPipe() throws Exception {
        Path childPid=directory.resolve("child.pid");
        Path script=Files.writeString(directory.resolve("fixture.py"), """
                import subprocess,json,sys,time,pathlib
                request=json.loads(sys.stdin.readline())
                child=subprocess.Popen(['sleep','60'])
                pathlib.Path('child.pid').write_text(str(child.pid))
                print(json.dumps({'jsonrpc':'2.0','id':request['id'],'result':{'serverInfo':{'name':'deepseek-harness-sdk-runtime'}}}),flush=True)
                time.sleep(60)
                """);
        Process root=new ProcessBuilder("python3",script.toString()).directory(directory.toFile()).start();
        Process unrelated = new ProcessBuilder("sleep", "60").start();
        ProcessHandle child=null;
        try(var sdk=new DshSdkProcess(root,new ObjectMapper(),"")) {
            sdk.initialize(Map.of(),Duration.ofSeconds(5));
            child=ProcessHandle.of(Long.parseLong(Files.readString(childPid))).orElseThrow();
            Thread.sleep(200); // permit the ownership monitor to observe a live tool child
            root.destroyForcibly();assertTrue(root.waitFor(2,TimeUnit.SECONDS));
            ProcessHandle ownedChild=child;
            assertTimeoutPreemptively(Duration.ofSeconds(4),()->{
                while(ownedChild.isAlive())Thread.sleep(25);
            });
            assertTrue(unrelated.isAlive(), "a sibling process is not owned by the crashed SDK");
        } finally { if(child!=null && child.isAlive())child.destroyForcibly();if(root.isAlive())root.destroyForcibly();unrelated.destroyForcibly(); }
    }
}
