package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Environment acceptance, not a substitute for real SDK/model workspace tasks. */
@EnabledIfEnvironmentVariable(named = "MATECLAW_DSH_ACCEPTANCE_DOCKER_IMAGE", matches = "sha256:[0-9a-f]{64}")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class DshDockerSharedWorkspaceAcceptanceTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String PROBE = """
            import fs from 'node:fs'; import cp from 'node:child_process';
            const root=process.argv[1],expected=process.argv[2];
            const m=await import('/opt/mateclaw-dsh/node_modules/@deepseek-ai/node-addon-system/lib/index.js');
            // Establish that the separate outside tmpfs is writable before confinement.
            fs.writeFileSync('/outside/baseline','outside');
            const code=`const fs=require('fs'),root=process.argv[1],expected=process.argv[2];
              if(fs.readFileSync(root+'/host.txt','utf8')!==expected)throw Error('HOST_INPUT_NOT_VISIBLE');
              fs.writeFileSync(root+'/container.txt',expected+'-container');
              for(const path of ['/outside/forbidden',root+'/escape/forbidden']) {
                try{fs.writeFileSync(path,'bad');throw Error('OUTSIDE_WRITE_ALLOWED')}
                catch(e){if(e.code!=='EACCES')throw e;}
              }
              console.log('SANDBOX_READ_WRITE_AND_DENIAL_OK');`;
            const p=cp.spawnSync(m.launcherPath(),[
              ...m.grantArgs({readOnly:['/'],readWrite:['/tmp',root]}),
              '--',process.execPath,'-e',code,root,expected
            ],{encoding:'utf8',timeout:15000});
            console.log(JSON.stringify({filesystem:fs.statfsSync(root).type.toString(16),
              exit:p.status,out:p.stdout,error:p.stderr}));
            process.exit(p.status===0?0:1);
            """;

    private List<String> daemon() throws Exception {
        String endpoint=System.getenv("DOCKER_HOST");
        if(endpoint==null||endpoint.isBlank())endpoint=json.readTree(command(List.of("docker","context","inspect","--format","{{json .Endpoints.docker.Host}}"))).asText();
        assertTrue(endpoint.startsWith("unix://"),"local Unix Docker endpoint required");
        return List.of("docker","--host",endpoint);
    }

    private String command(List<String> args) throws Exception {
        Process p=new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            assertTrue(p.waitFor(30,TimeUnit.SECONDS),"bounded Docker acceptance command");
            String out=new String(p.getInputStream().readNBytes(65537),java.nio.charset.StandardCharsets.UTF_8).trim();
            assertTrue(out.length()<=65536,"bounded diagnostic output");
            if(p.exitValue()!=0)throw new IOException(out);
            return out;
        } finally {if(p.isAlive())p.destroyForcibly();}
    }

    private String docker(List<String> daemon,String... args) throws Exception {
        var argv=new ArrayList<>(daemon);argv.addAll(List.of(args));return command(argv);
    }

    private String mount(Path path) throws IOException {
        String absolute=path.toRealPath().toString();
        return "type=bind,\"source="+absolute.replace("\"","\"\"")+"\",\"target="+absolute.replace("\"","\"\"")+"\"";
    }

    private String probe(List<String> daemon,List<String> mounts,String root,String expected) throws Exception {
        String image=System.getenv("MATECLAW_DSH_ACCEPTANCE_DOCKER_IMAGE");
        var identity=new com.sun.security.auth.module.UnixSystem();
        var args=new ArrayList<>(List.of("create","--pull=never","--label","vip.mateclaw.probe=landlock",
                "--read-only","--cap-drop=ALL","--security-opt=no-new-privileges",
                "--user",identity.getUid()+":"+identity.getGid(),
                "--tmpfs","/tmp:rw,exec,nosuid,nodev,size=128m",
                "--tmpfs","/outside:rw,nosuid,nodev,size=1m,mode=1777","--entrypoint","node"));
        args.addAll(mounts);args.addAll(List.of(image,"--input-type=module","-e",PROBE,root,expected));
        var argv=new ArrayList<>(daemon);argv.addAll(args);
        String id=command(argv);assertTrue(id.matches("[0-9a-f]{64}"));
        try {return docker(daemon,"start","--attach",id);}
        finally {docker(daemon,"rm","--force",id);}
    }

    private static void awaitContent(Path path,String expected) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while(System.nanoTime()<deadline) {
            if(Files.exists(path)&&expected.equals(Files.readString(path)))return;
            Thread.sleep(100);
        }
        fail("synchronized host content did not become visible within 30 seconds");
    }

    @Test
    void sameNativeSandboxAllowsExt4VolumeAndStillRejectsEscape() throws Exception {
        var daemon=daemon();
        String volume="mateclaw-landlock-control-"+UUID.randomUUID();
        String image=System.getenv("MATECLAW_DSH_ACCEPTANCE_DOCKER_IMAGE");
        docker(daemon,"volume","create","--label","vip.mateclaw.probe=landlock",volume);
        String binding="type=volume,source="+volume+",target=/workspace";
        try {
            docker(daemon,"run","--rm","--user","0:0","--cap-drop=ALL","--security-opt=no-new-privileges",
                    "--mount",binding,"--entrypoint","node",image,"-e",
                    "const fs=require('fs');fs.chmodSync('/workspace',0o777);fs.writeFileSync('/workspace/host.txt','control');fs.symlinkSync('/outside','/workspace/escape')");
            String result=probe(daemon,List.of("--mount",binding),"/workspace","control");
            assertTrue(result.contains("SANDBOX_READ_WRITE_AND_DENIAL_OK"),result);
            assertEquals("control-container",docker(daemon,"run","--rm","--read-only","--cap-drop=ALL",
                    "--mount",binding,"--entrypoint","node",image,"-e",
                    "process.stdout.write(require('fs').readFileSync('/workspace/container.txt','utf8'))"));
        } finally {docker(daemon,"volume","rm",volume);}
    }

    @Test
    @EnabledIfEnvironmentVariable(named="MATECLAW_DSH_ACCEPTANCE_SHARED_WORKSPACE",matches=".+")
    void nativeSandboxPreservesBidirectionalSharedWorkspaceAndRejectsEscape() throws Exception {
        Path parent=Path.of(System.getenv("MATECLAW_DSH_ACCEPTANCE_SHARED_WORKSPACE")).toRealPath();
        Path root=Files.createTempDirectory(parent,"dsh-landlock-");
        var daemon=daemon();
        try {
            Files.writeString(root.resolve("host.txt"),"first");
            Files.createSymbolicLink(root.resolve("escape"),Path.of("/outside"));
            String first=probe(daemon,List.of("--mount",mount(root)),root.toString(),"first");
            assertTrue(first.contains("SANDBOX_READ_WRITE_AND_DENIAL_OK"),first);
            awaitContent(root.resolve("container.txt"),"first-container");
            Files.writeString(root.resolve("host.txt"),"second");
            String second=probe(daemon,List.of("--mount",mount(root)),root.toString(),"second");
            assertTrue(second.contains("SANDBOX_READ_WRITE_AND_DENIAL_OK"),second);
            awaitContent(root.resolve("container.txt"),"second-container");
            String report=System.getenv("MATECLAW_DSH_ACCEPTANCE_REPORT_DIR");
            if(report!=null) {
                Path dir=Path.of(report);Files.createDirectories(dir);
                json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("shared-workspace.json").toFile(),
                        Map.of("success",true,"first",json.readTree(first),"second",json.readTree(second),
                                "hostVisible",true,"outsideWriteDenied",true,"symlinkEscapeDenied",true));
            }
        } finally {
            // Do not follow the deliberately escaping symlink or remove caller-owned files.
            try(var paths=Files.walk(root)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);
            }
        }
    }
}
