package vip.mate.skill.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillFileEntity;
import vip.mate.skill.repository.SkillFileMapper;
import vip.mate.skill.workspace.SkillFileSyncer;
import vip.mate.skill.workspace.SkillWorkspaceManager;
import vip.mate.skill.workspace.SkillWorkspaceProperties;
import vip.mate.skill.workspace.bundle.ClasspathBundleSource;
import vip.mate.skill.workspace.bundle.SkillBundleFiles;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

class SkillBundleBinaryTest {
    @TempDir Path temp;

    @Test
    void bundledPdfsPreserveRawHashSizeAndDecodedBytes() throws Exception {
        var source =
                new ClasspathBundleSource(
                        new PathMatchingResourcePatternResolver(), "skills/research-paper-writing");
        Map<String, byte[]> files = SkillBundleFiles.readDbEligibleBytes(source);
        var mapper = mock(SkillFileMapper.class);
        List<SkillFileEntity> stored = new ArrayList<>();
        when(mapper.selectList(any())).thenAnswer(call -> List.copyOf(stored));
        when(mapper.insert(any(SkillFileEntity.class)))
                .thenAnswer(
                        call -> {
                            stored.add(call.getArgument(0));
                            return 1;
                        });
        var service = new SkillFileService(mapper);
        service.applyBundleBytes(1L, files, false);
        int pdfs = 0;
        for (SkillFileEntity row : stored) {
            if (!row.getFilePath().endsWith(".pdf")) continue;
            byte[] raw = files.get(row.getFilePath());
            assertEquals("base64", row.getContentEncoding(), row.getFilePath());
            assertEquals(raw.length, row.getContentSize());
            assertEquals(SkillFileService.sha256Hex(raw), row.getSha256());
            assertArrayEquals(raw, row.contentBytes());
            pdfs++;
        }
        assertEquals(4, pdfs);
        assertEquals(0, service.applyBundleBytes(1L, files, false).rowsWritten());
        assertFalse(files.containsKey("SKILL.md"));
    }

    @Test
    void packagedJarReaderOnlyIngestsEligibleBucketsWithoutDecoding() throws Exception {
        byte[] binary = {0, -1, 1, 42};
        Path jar = temp.resolve("bundle.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String path :
                    List.of(
                            "skills/sample/",
                            "skills/sample/templates/",
                            "skills/sample/templates/file.pdf",
                            "skills/sample/fonts/",
                            "skills/sample/fonts/font.bin",
                            "skills/sample/SKILL.md")) {
                output.putNextEntry(new JarEntry(path));
                if (!path.endsWith("/")) output.write(binary);
                output.closeEntry();
            }
        }
        try (var loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
            var source =
                    new ClasspathBundleSource(
                            new PathMatchingResourcePatternResolver(loader), "skills/sample");
            Map<String, byte[]> files = SkillBundleFiles.readDbEligibleBytes(source);
            assertEquals(1, files.size());
            assertArrayEquals(binary, files.get("templates/file.pdf"));
        }
    }

    @Test
    void diskBackfillPreservesBinaryTemplateInsteadOfSkippingMalformedUtf8() throws Exception {
        var skill = new SkillEntity();
        skill.setId(10L);
        skill.setName("sample");
        skill.setWorkspaceId(1L);
        var skills = mock(SkillService.class);
        when(skills.listSkills()).thenReturn(List.of(skill));
        var mapper = mock(SkillFileMapper.class);
        List<SkillFileEntity> stored = new ArrayList<>();
        when(mapper.selectList(any())).thenAnswer(call -> List.copyOf(stored));
        when(mapper.insert(any(SkillFileEntity.class)))
                .thenAnswer(
                        call -> {
                            stored.add(call.getArgument(0));
                            return 1;
                        });
        var properties = new SkillWorkspaceProperties();
        properties.setRoot(temp.toString());
        var manager = new SkillWorkspaceManager(properties, mock(ApplicationEventPublisher.class));
        byte[] binary = {0, -1, 32, 12};
        Path template = temp.resolve("1/sample/templates/file.pdf");
        Files.createDirectories(template.getParent());
        Files.write(template, binary);
        var report =
                new SkillFileSyncer(skills, new SkillFileService(mapper), manager, properties)
                        .syncAll();
        assertEquals(1, report.filesBackfilledFromDisk());
        assertEquals(1, stored.size());
        assertArrayEquals(binary, stored.getFirst().contentBytes());
        assertArrayEquals(binary, Files.readAllBytes(template));
    }

    @Test
    void textApiRetainsUtf8AndBinaryTemplateEmptyBucketGuard() {
        var mapper = mock(SkillFileMapper.class);
        List<SkillFileEntity> stored = new ArrayList<>();
        when(mapper.selectList(any())).thenAnswer(call -> List.copyOf(stored));
        when(mapper.insert(any(SkillFileEntity.class)))
                .thenAnswer(
                        call -> {
                            stored.add(call.getArgument(0));
                            return 1;
                        });
        var service = new SkillFileService(mapper);
        service.applyBundleBytes(1L, Map.of("templates/file.pdf", new byte[] {0, -1}), false);
        service.applyBundleFiles(1L, Map.of("references/plain.txt", "plain λ\n"), false);
        verify(mapper, never()).deleteById(any(Long.class));
        assertEquals("utf8", stored.get(1).getContentEncoding());
        assertArrayEquals(
                "plain λ\n".getBytes(StandardCharsets.UTF_8), stored.get(1).contentBytes());
    }

    @Test
    void repairsLegacyControlBytesEvenWhenRawHashAlreadyMatches() {
        byte[] bytes = {'a', 0, 'b'};
        var row = new SkillFileEntity();
        row.setId(1L);
        row.setSkillId(1L);
        row.setFilePath("references/binary.dat");
        row.setContent("a\0b");
        row.setContentEncoding("utf8");
        row.setSha256(SkillFileService.sha256Hex(bytes));
        var mapper = mock(SkillFileMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        assertEquals(
                1,
                new SkillFileService(mapper)
                        .applyBundleBytes(1L, Map.of(row.getFilePath(), bytes), false)
                        .rowsWritten());
        verify(mapper).updateById(row);
        assertEquals("base64", row.getContentEncoding());
        assertArrayEquals(bytes, row.contentBytes());
    }
}
