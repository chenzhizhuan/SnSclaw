package vip.mate.skill.workspace;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.util.ReflectionTestUtils;

import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillFileEntity;
import vip.mate.skill.service.SkillFileService;
import vip.mate.skill.service.SkillService;
import vip.mate.skill.workspace.bundle.ClasspathBundleSource;
import vip.mate.skill.workspace.bundle.SkillBundleFiles;
import vip.mate.skill.workspace.bundle.SkillBundleMaterializer;
import vip.mate.skill.workspace.bundle.SkillBundleSource;
import vip.mate.skill.workspace.bundle.SkillBundleSource.BundleAsset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link BundledSkillSyncer} covering:
 *
 * <ul>
 *   <li>First install mirrors bundle scripts/references into the DB.</li>
 *   <li>Missing {@code scripts/} on disk triggers a self-heal copy even
 *       when the SKILL.md version is unchanged, archiving the old
 *       workspace first.</li>
 *   <li>A current workspace with all buckets present is a strict no-op —
 *       no disk copy, no DB writes.</li>
 * </ul>
 */
class BundledSkillSyncerTest {

    @TempDir
    Path tmp;

    private SkillWorkspaceProperties properties;
    private SkillWorkspaceManager workspaceManager;
    private SkillBundleMaterializer bundleMaterializer;
    private SkillService skillService;
    private SkillFileService skillFileService;
    private BundledSkillSyncer syncer;

    @BeforeEach
    void setUp() {
        properties = new SkillWorkspaceProperties();
        properties.setRoot(tmp.toString());
        properties.setBundledSkillsPath("skills");

        workspaceManager = new SkillWorkspaceManager(properties, mock(ApplicationEventPublisher.class));
        bundleMaterializer = new SkillBundleMaterializer();
        skillService = mock(SkillService.class);
        skillFileService = mock(SkillFileService.class);

        syncer = new BundledSkillSyncer(
                properties,
                workspaceManager,
                bundleMaterializer,
                mock(ApplicationEventPublisher.class),
                skillService,
                skillFileService
        );
    }

    @Test
    @DisplayName("Sync applies classpath bundle files to DB for existing skill entities")
    void syncAppliesBundleFilesToDb() {
        SkillEntity pptxSkill = new SkillEntity();
        pptxSkill.setId(100L);
        pptxSkill.setName("pptx");

        when(skillService.findByName("pptx")).thenReturn(pptxSkill);

        List<String> synced = syncer.sync();

        assertTrue(synced.contains("pptx"));
        verify(skillFileService, atLeastOnce()).applyBundleBytes(eq(100L), anyMap(), eq(false));
    }

    @Test
    @DisplayName("Sync self-heals a workspace missing scripts/ despite unchanged version")
    void syncForcesDiskCopyWhenScriptsDirMissing() throws IOException {
        Path pptxDir = tmp.resolve("1").resolve("pptx");
        Files.createDirectories(pptxDir);
        // Same SKILL.md as the bundle (identical version) but no scripts/
        // directory — the state left behind by a build that shipped without
        // bundle scripts.
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("skills/pptx/SKILL.md")) {
            assertNotNull(is, "bundled pptx SKILL.md must exist on the test classpath");
            Files.write(pptxDir.resolve("SKILL.md"), is.readAllBytes());
        }

        SkillEntity pptxSkill = new SkillEntity();
        pptxSkill.setId(100L);
        pptxSkill.setName("pptx");
        when(skillService.findByName("pptx")).thenReturn(pptxSkill);

        List<String> synced = syncer.sync();

        assertTrue(synced.contains("pptx"), "Should re-sync when scripts directory is missing");
        assertTrue(
                Files.isDirectory(pptxDir.resolve("scripts")),
                "scripts directory should now be on disk");
        verify(skillFileService, atLeastOnce()).applyBundleBytes(eq(100L), anyMap(), eq(false));
        try (var archived = Files.list(tmp.resolve("1").resolve(".archived"))) {
            assertTrue(archived.anyMatch(p -> p.getFileName().toString().startsWith("pptx-")),
                    "old workspace should be archived, not overwritten in place");
        }
    }

    @Test
    @DisplayName("Sync is a strict no-op when the workspace is current with all buckets present")
    void syncSkipsDbWhenWorkspaceCurrent() {
        // First pass installs every bundled skill into the empty root.
        syncer.sync();
        clearInvocations(skillFileService);

        SkillEntity pptxSkill = new SkillEntity();
        pptxSkill.setId(100L);
        pptxSkill.setName("pptx");
        when(skillService.findByName("pptx")).thenReturn(pptxSkill);

        List<String> second = syncer.sync();

        assertFalse(second.contains("pptx"), "Unchanged workspace should not re-sync");
        verify(skillFileService, never()).applyBundleBytes(anyLong(), anyMap(), anyBoolean());
    }

    @Test
    void retryRepairsMissingPdfRowsWithoutOverwritingCanonicalOrLocalEdits() throws Exception {
        SkillEntity skill = new SkillEntity();
        skill.setId(100L);
        skill.setName("research-paper-writing");
        skill.setBuiltin(true);
        when(skillService.findByName(skill.getName())).thenReturn(skill);
        doThrow(new IllegalStateException("prior binary persistence failure"))
                .when(skillFileService)
                .applyBundleBytes(eq(100L), anyMap(), eq(false));
        assertThrows(IllegalStateException.class, () -> syncer.sync());
        Path workspace = tmp.resolve("1/research-paper-writing");
        var source =
                new ClasspathBundleSource(
                        new PathMatchingResourcePatternResolver(), "skills/research-paper-writing");
        var raw = SkillBundleFiles.readDbEligibleBytes(source);
        var prior = new ArrayList<SkillFileEntity>();
        for (String path : raw.keySet()) {
            if (path.endsWith(".pdf")) continue;
            var row = new SkillFileEntity();
            row.setFilePath(path);
            row.setContent("canonical user edit");
            prior.add(row);
        }
        when(skillFileService.listBySkillId(100L)).thenReturn(prior);
        when(skillFileService.upsertBytes(eq(100L), anyString(), any(byte[].class)))
                .thenAnswer(
                        call -> {
                            var row = new SkillFileEntity();
                            row.setFilePath(call.getArgument(1));
                            prior.add(row);
                            return row;
                        });
        String editedPath = "templates/colm2025/colm2025_conference.pdf";
        byte[] edited = {0, -1, 42};
        Files.write(workspace.resolve(editedPath), edited);
        clearInvocations(skillFileService);
        assertFalse(syncer.sync().contains(skill.getName()));
        verify(skillFileService, times(4)).upsertBytes(eq(100L), anyString(), any(byte[].class));
        for (var entry : raw.entrySet()) {
            if (!entry.getKey().endsWith(".pdf")) continue;
            verify(skillFileService)
                    .upsertBytes(
                            eq(100L),
                            eq(entry.getKey()),
                            aryEq(entry.getKey().equals(editedPath) ? edited : entry.getValue()));
        }
        verify(skillFileService, never()).applyBundleBytes(anyLong(), anyMap(), anyBoolean());
        assertArrayEquals(edited, Files.readAllBytes(workspace.resolve(editedPath)));
    }

    @Test
    void missingOrNonBuiltinEntityDoesNotRepair() {
        var source = mock(SkillBundleSource.class);
        ReflectionTestUtils.invokeMethod(syncer, "repairMissingBundleRows", "absent", source, tmp);
        var unseeded = new SkillEntity();
        unseeded.setBuiltin(true);
        when(skillService.findByName("unseeded")).thenReturn(unseeded);
        ReflectionTestUtils.invokeMethod(syncer,
                "repairMissingBundleRows", "unseeded", source, tmp);
        var skill = new SkillEntity();
        skill.setId(100L);
        skill.setBuiltin(false);
        when(skillService.findByName("dynamic")).thenReturn(skill);
        ReflectionTestUtils.invokeMethod(syncer, "repairMissingBundleRows", "dynamic", source, tmp);
        verifyNoInteractions(source, skillFileService);
    }

    @Test
    void missingDiskFileAndSymbolicParentsAreSkipped() throws Exception {
        var skill = new SkillEntity();
        skill.setId(100L);
        skill.setBuiltin(true);
        when(skillService.findByName("builtin")).thenReturn(skill);
        when(skillFileService.listBySkillId(100L)).thenReturn(List.of());
        Path target = tmp.resolve("builtin");
        Files.createDirectories(target.resolve("templates"));
        byte[] bytes = {0, -1, 42};
        Files.write(target.resolve("templates/safe.pdf"), bytes);
        Path outside = tmp.resolve("outside");
        Files.createDirectories(outside);
        Files.write(outside.resolve("unsafe.pdf"), bytes);
        Files.createSymbolicLink(target.resolve("references"), outside);
        var source = mock(SkillBundleSource.class);
        when(source.assets())
                .thenReturn(
                        List.of(
                                new BundleAsset("templates/safe.pdf", null),
                                new BundleAsset("templates/missing.pdf", null),
                                new BundleAsset("references/unsafe.pdf", null)));
        ReflectionTestUtils.invokeMethod(
                syncer, "repairMissingBundleRows", "builtin", source, target);
        verify(skillFileService).upsertBytes(eq(100L), eq("templates/safe.pdf"), aryEq(bytes));
        verify(skillFileService, times(1)).upsertBytes(anyLong(), anyString(), any(byte[].class));
        assertFalse(Files.exists(target.resolve("templates/missing.pdf")));
    }

    @Test
    void newerLocalVersionKeepsLocalBytesAndDoesNotUpgrade() throws Exception {
        var skill = new SkillEntity();
        skill.setId(100L);
        skill.setName("research-paper-writing");
        skill.setBuiltin(true);
        when(skillService.findByName(skill.getName())).thenReturn(skill);
        syncer.sync();
        Path workspace = tmp.resolve("1/research-paper-writing");
        Files.writeString(
                workspace.resolve("SKILL.md"),
                "---\nname: research-paper-writing\nversion: 9999.0.0\n---\nlocal manifest");
        String localPath = "templates/colm2025/colm2025_conference.pdf";
        byte[] local = {42, 0, -1};
        Files.write(workspace.resolve(localPath), local);
        var rows = new ArrayList<SkillFileEntity>();
        when(skillFileService.listBySkillId(100L)).thenReturn(rows);
        when(skillFileService.upsertBytes(eq(100L), anyString(), any(byte[].class)))
                .thenAnswer(
                        call -> {
                            var row = new SkillFileEntity();
                            row.setFilePath(call.getArgument(1));
                            rows.add(row);
                            return row;
                        });
        clearInvocations(skillFileService);
        assertFalse(syncer.sync().contains(skill.getName()));
        verify(skillFileService).upsertBytes(eq(100L), eq(localPath), aryEq(local));
        verify(skillFileService, never()).applyBundleBytes(anyLong(), anyMap(), anyBoolean());
        assertArrayEquals(local, Files.readAllBytes(workspace.resolve(localPath)));
        assertFalse(Files.exists(tmp.resolve("1/.archived")));
    }
}
