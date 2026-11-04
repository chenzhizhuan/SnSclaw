package vip.mate.agent.runtime.dsh;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DshDockerProcessTest {
    private Process cli(CompletableFuture<Process> exited) {var p=mock(Process.class);when(p.onExit()).thenReturn(exited);return p;}
    @Test void cleanupFailureIsVisibleAndRetriedRatherThanMarkedRemoved() {
        var p=cli(new CompletableFuture<>());var count=new AtomicInteger();
        var owned=new DshDockerProcess(p,()->{if(count.incrementAndGet()==1)throw new Exception("unavailable");return "id";},"id");
        assertThrows(IllegalStateException.class,owned::destroy);verify(p,never()).destroy();
        owned.destroy();owned.destroy();assertEquals(2,count.get());
    }
    @Test void losingTheAttachClientRemovesTheExactContainerWithoutPolling() throws Exception {
        var exited=new CompletableFuture<Process>();var p=cli(exited);var removed=new CountDownLatch(1);
        new DshDockerProcess(p,()->{removed.countDown();return "owned-id";},"owned-id");
        exited.complete(p);assertTrue(removed.await(2,TimeUnit.SECONDS));
    }
    @Test void removalOfTheWrongIdentityCannotConfirmCleanup() {
        var p=cli(new CompletableFuture<>());var owned=new DshDockerProcess(p,()->"different-id","owned-id");
        assertThrows(IllegalStateException.class,owned::destroyForcibly);verify(p,never()).destroyForcibly();
    }
    @Test void uncertainOwnershipRetainsLeaseUntilSuccessfulRetry() {
        var p=cli(new CompletableFuture<>());var attempts=new AtomicInteger();var releases=new AtomicInteger();
        var owned=new DshDockerProcess(p,()->{if(attempts.incrementAndGet()<2)throw new Exception("daemon unavailable");return "id";},"id");
        owned.afterCleanup(releases::incrementAndGet);
        assertThrows(IllegalStateException.class,owned::destroy);assertEquals(0,releases.get());
        owned.destroy();owned.destroy();assertEquals(1,releases.get());
        var late=new AtomicInteger();owned.afterCleanup(late::incrementAndGet);assertEquals(1,late.get());
    }
    @Test void confirmedRemovalStillRetriesFailedLeaseBookkeeping() {
        var p=cli(new CompletableFuture<>());var removals=new AtomicInteger();var releases=new AtomicInteger();
        var owned=new DshDockerProcess(p,()->{removals.incrementAndGet();return "id";},"id");
        owned.afterCleanup(()->{if(releases.incrementAndGet()==1)throw new IllegalStateException("store unavailable");});
        assertThrows(IllegalStateException.class,owned::destroy);assertFalse(owned.cleanupConfirmed());
        owned.destroy();assertTrue(owned.cleanupConfirmed());assertEquals(1,removals.get());assertEquals(2,releases.get());
    }
}
