package vip.mate.agent.runtime.dsh;

import java.io.*;
import java.util.concurrent.*;

/** Owns one exact container created before SDK startup. Removal is idempotent and fail-visible. */
final class DshDockerProcess extends Process {
    interface Removal { String remove() throws Exception; }
    private final Process delegate;
    private final Removal removal;
    private final String id;
    private boolean removed;
    private final java.util.Deque<Runnable> afterCleanup = new java.util.ArrayDeque<>();
    synchronized void afterCleanup(Runnable action) {
        afterCleanup.addLast(action);
        if (removed) finishBookkeeping();
    }
    private void finishBookkeeping() {
        while (!afterCleanup.isEmpty()) {
            afterCleanup.getFirst().run();
            afterCleanup.removeFirst();
        }
    }
    DshDockerProcess(Process delegate,Removal removal,String id) {
        this.delegate=delegate;this.removal=removal;this.id=id;
        delegate.onExit().thenRunAsync(()->{try{cleanup();}catch(RuntimeException ignored){/* close/cancel retries and exposes failure */}});
    }
    synchronized boolean cleanupConfirmed() { return removed && afterCleanup.isEmpty(); }
    private synchronized void cleanup() {
        try {
            if (!removed) {
                String result=removal.remove();
                if(!id.equals(result.trim()))throw new IOException("container removal identity mismatch");
                removed=true;
            }
            finishBookkeeping();
        } catch(Exception error) {throw new IllegalStateException("DSH_DOCKER_CLEANUP_UNCONFIRMED",error);}
    }
    @Override public OutputStream getOutputStream(){return delegate.getOutputStream();}
    @Override public InputStream getInputStream(){return delegate.getInputStream();}
    @Override public InputStream getErrorStream(){return delegate.getErrorStream();}
    @Override public int waitFor() throws InterruptedException {int code=delegate.waitFor();cleanup();return code;}
    @Override public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{boolean exited=delegate.waitFor(timeout,unit);if(exited)cleanup();return exited;}
    @Override public int exitValue(){return delegate.exitValue();}
    @Override public boolean isAlive(){return delegate.isAlive();}
    @Override public long pid(){return delegate.pid();}
    @Override public ProcessHandle toHandle(){return delegate.toHandle();}
    @Override public void destroy(){cleanup();delegate.destroy();}
    @Override public Process destroyForcibly(){cleanup();delegate.destroyForcibly();return this;}
}
