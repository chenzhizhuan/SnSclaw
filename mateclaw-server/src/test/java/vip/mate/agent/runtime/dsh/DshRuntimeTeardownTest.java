package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.runtime.contract.*;
import vip.mate.agent.runtime.dsh.management.*;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.llm.service.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DshRuntimeTeardownTest {
    @TempDir Path temp;
    @Test void cleanupFailureCannotPublishCompleted() throws Exception { check(false); }
    @Test void cleanupFailureCannotPublishCancelled() throws Exception { check(true); }
    @Test void failedLeaseReleaseCannotPublishCompletedAndRemainsRetryable() throws Exception { check(false,true); }
    void check(boolean cancel) throws Exception { check(cancel,false); }
    void check(boolean cancel, boolean bookkeepingFailure) throws Exception {
        Path script=temp.resolve("sdk");
        Files.writeString(script, """
                #!/bin/sh
                IFS= read -r initialize
                printf '%s\\n' "$initialize" > initialize.json
                id=$(printf '%s' "$initialize" | sed -n 's/.*"id":"\\([^"]*\\)".*/\\1/p')
                printf '{"jsonrpc":"2.0","id":"%s","result":{"serverInfo":{"name":"deepseek-harness-sdk-runtime"}}}\\n' "$id"
                IFS= read -r prompt
                sleep 1
                session=$(printf '%s' "$prompt" | sed -n 's/.*"sessionId":"\\([^"]*\\)".*/\\1/p')
                printf '%s\\n' '{"jsonrpc":"2.0","id":"prompt-limit-test","result":{"messageId":"m1"}}'
                printf '{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"%s","event":{"seq":1,"type":"agent/inbox/spliced","data":{"inserted":[{"id":"m1"}]}}}}\\n' "$session"
                printf '{"jsonrpc":"2.0","method":"session.event","params":{"sessionId":"%s","event":{"seq":2,"type":"turn/end","data":{"reason":{"kind":"completed"}}}}}\\n' "$session"
                printf '{"jsonrpc":"2.0","method":"session.status","params":{"sessionId":"%s","status":"idle"}}\\n' "$session"
                IFS= read -r shutdown
                """);

        script.toFile().setExecutable(true);
        var config=mock(DshRuntimeConfigService.class);
        when(config.resolve()).thenReturn(new DshRuntimeConfiguration(script.toString(),"",temp.toString(),"","model","","sdk",List.of(),temp.resolve("homes").toString()));
        var service=new DshRuntimeService(new ObjectMapper(),mock(ModelConfigService.class),mock(ModelProviderService.class),config,new ConversationWindowProperties());
        var supervisor=mock(DshProcessSupervisor.class);
        var failRemoval=new AtomicBoolean(true);
        var releases=new AtomicInteger();
        var store=mock(DshGenerationStore.class);var lease=mock(DshGenerationStore.Lease.class);
        when(config.generations()).thenReturn(store);when(store.acquireLease(anyString(),anyString())).thenReturn(lease);
        doAnswer(invocation->{if(bookkeepingFailure && failRemoval.get())throw new IllegalStateException("store unavailable");releases.incrementAndGet();return null;}).when(lease).close();
        var raw=new AtomicReference<Process>();
        when(supervisor.start(any(),any())).thenAnswer(invocation->{
            Process p=new ProcessBuilder(script.toString()).directory(temp.toFile()).start();raw.set(p);
            return new DshDockerProcess(p,()->{if(!bookkeepingFailure && failRemoval.get())throw new Exception("daemon unavailable");return "id";},"id");
        });
        service.setProcessSupervisor(supervisor);
        var connection=service.start(new RuntimeSession("test","limit-test",1L,2L,"model",temp,Map.of()));
        var events=new CopyOnWriteArrayList<RuntimeEvent>();var error=new AtomicReference<Throwable>();
        var ready=new CountDownLatch(1);var done=new CountDownLatch(1);
        connection.prompt("fixture").subscribe(e->{events.add(e);if(e.type()==RuntimeEventType.RUNTIME_READY)ready.countDown();},e->{error.set(e);done.countDown();},done::countDown);
        try {
            assertTrue(ready.await(5,TimeUnit.SECONDS));
            if(cancel)assertThrows(RuntimeException.class,()->connection.cancel().block(Duration.ofSeconds(3)));
            assertTrue(done.await(8,TimeUnit.SECONDS));assertNotNull(error.get());
            assertTrue(error.get().getMessage().contains("DSH_DOCKER_CLEANUP_UNCONFIRMED"));
            assertFalse(events.stream().anyMatch(e->e.type()==RuntimeEventType.COMPLETED||e.type()==RuntimeEventType.CANCELLED));
        } finally {failRemoval.set(false);connection.cancel().block();if(raw.get()!=null)raw.get().destroyForcibly();}
        assertEquals(1,releases.get(),"lease must release once after confirmed cleanup/retry");
    }
    @Test void healthCleanupFailureRetainsLeaseAndNextCheckCanRetryRemoval() throws Exception {
        var configuration=new DshRuntimeConfiguration("/bin/sh","",temp.toString(),"","model","","sdk",List.of(),temp.resolve("homes").toString());
        var config=mock(DshRuntimeConfigService.class);when(config.resolve()).thenReturn(configuration);
        var service=new DshRuntimeService(new ObjectMapper(),mock(ModelConfigService.class),mock(ModelProviderService.class),config,new ConversationWindowProperties());
        var p=mock(Process.class);when(p.onExit()).thenReturn(new CompletableFuture<>());
        when(p.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
        when(p.getErrorStream()).thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
        when(p.getOutputStream()).thenReturn(new java.io.ByteArrayOutputStream());
        var fail=new AtomicBoolean(true);var releases=new AtomicInteger();
        var owned=new DshDockerProcess(p,()->{if(fail.get())throw new Exception("daemon down");return "id";},"id");
        var supervisor=mock(DshProcessSupervisor.class);when(supervisor.start(any(),any())).thenReturn(owned);service.setProcessSupervisor(supervisor);
        assertEquals(false,service.testConnection(configuration).get("success"));
        service.releaseHealthLease(releases::incrementAndGet);assertEquals(0,releases.get());
        assertThrows(IllegalStateException.class,service::retryPendingHealthCleanup);assertEquals(0,releases.get());
        fail.set(false);service.retryPendingHealthCleanup();assertEquals(1,releases.get());
        service.retryPendingHealthCleanup();assertEquals(1,releases.get());
    }
}
