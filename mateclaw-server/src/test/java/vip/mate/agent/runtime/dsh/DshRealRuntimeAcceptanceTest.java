package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.runtime.contract.*;
import vip.mate.agent.runtime.dsh.management.*;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.llm.model.*;
import vip.mate.llm.service.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in, real package/model checks. Reads an explicitly supplied H2 database read-only. */
@EnabledIfEnvironmentVariable(named="MATECLAW_DSH_ACCEPTANCE_EXECUTABLE", matches=".+")
@Timeout(value=15, unit=TimeUnit.MINUTES)
class DshRealRuntimeAcceptanceTest {
    @TempDir Path temp;
    private DshRuntimeService service;
    private RuntimeSession session;
    private final ObjectMapper json = new ObjectMapper();
    private final List<ProcessHandle> owned = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        String url = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com/anthropic");
        String database = System.getenv("MATECLAW_DSH_ACCEPTANCE_DATABASE");
        if (database != null) {
            try (var c=DriverManager.getConnection("jdbc:h2:file:"+database+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;ACCESS_MODE_DATA=r;IFEXISTS=TRUE", "sa", "");
                 var s=c.prepareStatement("select api_key,base_url from mate_model_provider where provider_id='deepseek'");var r=s.executeQuery()) {
                assertTrue(r.next(), "configured DeepSeek provider required"); key=r.getString(1);url=r.getString(2);
            }
        }
        assertTrue(key != null && !key.isBlank(), "real model credential required");
        var config = mock(DshRuntimeConfigService.class);
        when(config.resolve()).thenReturn(new DshRuntimeConfiguration(System.getenv("MATECLAW_DSH_ACCEPTANCE_EXECUTABLE"), "", temp.toString(), url, "deepseek-v4-flash", key, "sdk", List.of(), temp.resolve("homes").toString()));
        var models=mock(ModelConfigService.class);var model=new ModelConfigEntity();model.setModelName("deepseek-v4-flash");model.setMaxTokens(4096);model.setMaxInputTokens(128000);
        when(models.resolveModel(any())).thenReturn(model);
        service=new DshRuntimeService(json, models, mock(ModelProviderService.class), config, new ConversationWindowProperties());
        session=new RuntimeSession("acceptance", "acceptance-"+UUID.randomUUID(), 991L, 992L, "deepseek-v4-flash", temp, Map.of());
    }
    @AfterEach void cleanup() { owned.forEach(p->{if(p.isAlive())p.destroyForcibly();}); }
    private void record(String name, Object data) throws Exception {
        String report = System.getenv("MATECLAW_DSH_ACCEPTANCE_REPORT_DIR");
        if (report != null) { Path root=Path.of(report); Files.createDirectories(root); json.writerWithDefaultPrettyPrinter().writeValue(root.resolve(name+".json").toFile(),data); }
    }
    private AgentRuntimeConnection connection() { return service.start(session); }
    private List<ProcessHandle> processes() { return ProcessHandle.current().descendants().filter(p->p.info().commandLine().orElse("").contains("--profile sdk")).toList(); }
    private static void await(java.util.function.BooleanSupplier condition, Duration timeout) throws Exception {
        long end=System.nanoTime()+timeout.toNanos();while(!condition.getAsBoolean() && System.nanoTime()<end)Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), "bounded condition did not become true");
    }
    private void blockedTurn(boolean crash) throws Exception {
        var events=new CopyOnWriteArrayList<RuntimeEvent>();var error=new AtomicReference<Throwable>();var done=new CountDownLatch(1);
        var c=connection();String file=crash?"crash.pid":"cancel.pid";
        var subscription=c.prompt("Use bash to execute exactly: echo $$ > "+file+"; sleep 90; printf UNEXPECTED. Run in the current directory only. Do not use background execution. After the command reply UNEXPECTED.").subscribe(events::add,e->{error.set(e);done.countDown();},done::countDown);
        try {
            await(()->Files.exists(temp.resolve(file)),Duration.ofSeconds(120));
            var roots=processes();assertEquals(1,roots.size(),"isolated SDK root required");
            owned.addAll(roots);roots.forEach(p->owned.addAll(p.descendants().toList()));
            assertTrue(owned.size()>1,"actual tool descendants must exist");
            if(crash) roots.getFirst().destroyForcibly();
            else assertTimeoutPreemptively(Duration.ofSeconds(10),()->c.cancel().block());
            assertTrue(done.await(15,TimeUnit.SECONDS),"stream must finish after termination");
            await(()->owned.stream().noneMatch(ProcessHandle::isAlive),Duration.ofSeconds(5));
            assertFalse(events.stream().anyMatch(e->e.type()==RuntimeEventType.COMPLETED));
            if(!crash){assertNull(error.get());assertEquals(1,events.stream().filter(e->e.type()==RuntimeEventType.CANCELLED).count());}
            else assertTrue(error.get()!=null || events.stream().anyMatch(e->e.type()==RuntimeEventType.FAILED));
            var recovered=connection().prompt("Return exactly the nine uppercase ASCII characters RECOVERED with no punctuation, whitespace, Markdown or explanation. Do not call tools.").collectList().block(Duration.ofSeconds(120));
            assertNotNull(recovered);assertEquals(1,recovered.stream().filter(e->e.type()==RuntimeEventType.COMPLETED).count());
            assertEquals("RECOVERED",recovered.stream().filter(e->e.type()==RuntimeEventType.ASSISTANT_DELTA).map(RuntimeEvent::text).reduce("",String::concat));
            record(crash?"crash":"cancel",Map.of("success",true,"ownedPids",owned.stream().map(ProcessHandle::pid).toList(),"allExited",owned.stream().noneMatch(ProcessHandle::isAlive),"events",events,"recoveryEvents",recovered));
        } finally { subscription.dispose();c.close(); }
    }
    @Test void explicitCancellationStopsRealToolTreeAndNextConnectionRecovers() throws Exception { blockedTurn(false); }
    @Test void unexpectedRootExitStopsRealToolTreeAndNextConnectionRecovers() throws Exception { blockedTurn(true); }

    @Test void twentyFreshProcessRoundsRecoverFromWorkspaceCheckpoints() throws Exception {
        int dwell=Integer.parseInt(System.getenv().getOrDefault("MATECLAW_DSH_ACCEPTANCE_DWELL_SECONDS","15"));
        assertTrue(dwell>=5 && dwell<=30,"dwell must be between 5 and 30 seconds");
        int rounds=Integer.parseInt(System.getenv().getOrDefault("MATECLAW_DSH_ACCEPTANCE_ROUNDS","20"));
        assertEquals(20,rounds,"this acceptance scenario requires exactly twenty rounds");
        long start=System.nanoTime();
        for(int i=1;i<=rounds;i++) {
            // Recreate the application connection; each prompt already creates a fresh SDK process/session.
            var c=connection();
            try {
                String prompt="Continue the checkpoint task in the current directory. Use bash/python to read ledger.txt if it exists (empty if absent), require lines 1 through "+(i-1)+" exactly once, append only the next line "+i+", then sleep "+dwell+" seconds and read back/validate lines 1 through "+i+". Never overwrite prior lines or modify any other file. Reply exactly ROUND_"+i+"_OK after verification.";
                var events=c.prompt(prompt).collectList().block(Duration.ofSeconds(180));assertNotNull(events);
                assertEquals(1,events.stream().filter(e->e.type()==RuntimeEventType.COMPLETED).count(),"round "+i);
                assertTrue(events.stream().anyMatch(e->e.type()==RuntimeEventType.TOOL_FINISHED),"real tool receipt required");
                assertEquals("ROUND_"+i+"_OK",events.stream().filter(e->e.type()==RuntimeEventType.ASSISTANT_DELTA).map(RuntimeEvent::text).reduce("",String::concat));
                assertEquals(java.util.stream.IntStream.rangeClosed(1,i).mapToObj(String::valueOf).toList(),Files.readAllLines(temp.resolve("ledger.txt")));
                record("round-"+i,Map.of("round",i,"success",true,"ledger",Files.readAllLines(temp.resolve("ledger.txt")),"events",events));
                System.out.println("DSH_REAL_ROUND "+i+" passed");
            } finally {c.close();}
        }
        long elapsed=Duration.ofNanos(System.nanoTime()-start).toSeconds();
        assertTrue(elapsed >= (long)rounds*dwell,"the sustained run must include the configured dwell time");
        record("multiturn",Map.of("rounds",rounds,"dwellSeconds",dwell,"elapsedSeconds",elapsed,"success",true));
        System.out.println("DSH_REAL_MULTITURN rounds="+rounds+" elapsedSeconds="+elapsed);
    }

    @Test void checkpointCommittedBeforeCrashIsVerifiedWithoutReplayingTheWrite() throws Exception {
        var c=connection();var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        var events=new CopyOnWriteArrayList<RuntimeEvent>();
        var subscription=c.prompt("Use bash to run exactly: printf '1\\n' > recovery-ledger.txt; echo $$ > recovery.pid; sleep 90; printf UNEXPECTED. Use only the current directory and do not use background execution.")
                .subscribe(events::add,e->{failure.set(e);done.countDown();},done::countDown);
        try {
            await(()->Files.exists(temp.resolve("recovery.pid")),Duration.ofSeconds(120));
            assertEquals(List.of("1"),Files.readAllLines(temp.resolve("recovery-ledger.txt")));
            var roots=processes();assertEquals(1,roots.size());owned.addAll(roots);roots.forEach(p->owned.addAll(p.descendants().toList()));
            roots.getFirst().destroyForcibly();
            assertTrue(done.await(15,TimeUnit.SECONDS));assertNotNull(failure.get());
            await(()->owned.stream().noneMatch(ProcessHandle::isAlive),Duration.ofSeconds(5));
            byte[] before=Files.readAllBytes(temp.resolve("recovery-ledger.txt"));
            try(var recovery=connection()) {
                var resumed=recovery.prompt("Recover the interrupted file task. Use a tool to read recovery-ledger.txt in the current directory. The write is already committed if it contains exactly one line equal to 1; in that case do not append, overwrite or repeat the write. Verify it and return exactly the thirteen uppercase ASCII characters CHECKPOINT_OK, without punctuation, Markdown or explanation.")
                        .collectList().block(Duration.ofSeconds(120));
                assertNotNull(resumed);assertTrue(resumed.stream().anyMatch(e->e.type()==RuntimeEventType.TOOL_FINISHED));
                assertEquals(1,resumed.stream().filter(e->e.type()==RuntimeEventType.COMPLETED).count());
                assertEquals("CHECKPOINT_OK",resumed.stream().filter(e->e.type()==RuntimeEventType.ASSISTANT_DELTA).map(RuntimeEvent::text).reduce("",String::concat));
                assertArrayEquals(before,Files.readAllBytes(temp.resolve("recovery-ledger.txt")));
                record("checkpoint-recovery",Map.of("success",true,"writeUnchanged",true,"events",events,"recoveryEvents",resumed));
            }
        } finally {subscription.dispose();c.close();}
    }
}
