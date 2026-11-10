package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.config.SubtitleUploadProperties;
import com.medianexus.orchestrator.controller.SubtitleUploadController;
import com.medianexus.orchestrator.dto.subtitle.SubtitleCommitRequest;
import com.medianexus.orchestrator.dto.subtitle.SubtitleCommitRequest.Selection;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.mapper.SubtitleUploadMapper;
import com.medianexus.orchestrator.mapper.SubtitleUploadLogMapper;
import com.medianexus.orchestrator.model.SubtitleUpload;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.support.IngestTargetFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class SubtitlePreparationServiceTest {
    private final OpenListClient openList = mock(OpenListClient.class);
    private final SubtitleUploadMapper uploads = mock(SubtitleUploadMapper.class);
    private final AuthService auth = mock(AuthService.class);
    private final AutoSymlinkRefreshService as = mock(AutoSymlinkRefreshService.class);
    private final MovieSeriesFileRenameService rename = mock(MovieSeriesFileRenameService.class);
    private final SubtitleArchiveExtractor extractor = new SubtitleArchiveExtractor(new SubtitleUploadProperties());
    private final QueueExecutor executor = new QueueExecutor();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final OpenListProperties properties = new OpenListProperties();
    private final SubtitleUploadService service = new SubtitleUploadService(uploads, mock(SubtitleUploadLogMapper.class), openList,
            properties, rename, auth, extractor, new SubtitleFilenamePlanner(), json, as, executor, new IngestTargetFixtures().registry);
    private final SubtitleUploadController controller = new SubtitleUploadController(service);
    private final User owner = new User();
    private List<OpenListFileInfo> remote;
    private static final String MOVIE = "/movies/Movie (2024)";

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SubtitleUpload.class);
        owner.setId(1L);
        owner.setRole("USER");
        when(auth.requireCurrentUser()).thenReturn(owner);
        properties.setMovieRootPath("/movies");
        properties.setTvRootPath("/tv");
        when(openList.normalizePath(anyString())).thenAnswer(call -> call.getArgument(0));
        when(openList.joinPath(anyString(), anyString())).thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1));
        when(openList.pathExists(anyString())).thenReturn(true);
        when(rename.movieFolderName("Movie", 2024)).thenReturn("Movie (2024)");
        when(rename.seriesFolderName("Show")).thenReturn("Show");
        when(rename.seasonFolderName(1)).thenReturn("Season 01");
        when(rename.isVideo(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).endsWith(".mkv"));
        when(rename.episodeNumber(anyString(), anyInt())).thenAnswer(call -> {
            String name = call.getArgument(0);
            return name.contains("S01E01") ? Optional.of(1) : Optional.empty();
        });
        remote = new ArrayList<>(List.of(video("Actual.mkv", 100L)));
        when(openList.listFiles(anyString(), eq(true))).thenAnswer(call -> List.copyOf(remote));
        when(as.refreshForPath(anyString(), anyString())).thenReturn(
                new AutoSymlinkRefreshService.RefreshOutcome(AutoSymlinkRefreshService.Status.SUBMITTED, "AS 已提交", null));
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() throws Exception {
        var field = SubtitleUploadService.class.getDeclaredField("preparations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> pending = (Map<String, Object>) field.get(service);
        for (String id : List.copyOf(pending.keySet())) expire(id);
    }

    @Test
    void controllerPreviewCommitAndCheckActuallyWriteOnlySelectedFileAndCleanLocal() throws Exception {
        byte[] zip = SubtitleArchiveExtractorTest.zip(Map.of("简体/01.srt", SubtitleArchiveExtractorTest.SRT,
                "繁体/01.srt", SubtitleArchiveExtractorTest.SRT), StandardCharsets.UTF_8);
        var preview = controller.prepare(SubtitleArchiveExtractorTest.upload("original.zip", zip), "movie", "Movie", null, 2024, null, null, "unknown").data();
        assertThat(preview.files()).hasSize(2).allMatch(file -> file.status().equals("READY"));
        verifyNoInteractions(uploads, as);
        String serialized = json.writeValueAsString(preview);
        assertThat(serialized).contains("expires_at", "target_path", "language_source", "episode_number", "skipped_entries");
        assertThat(json.readTree(serialized).path("expires_at").isTextual()).isTrue();
        var selected = preview.files().stream().filter(file -> file.language().equals("chs")).findFirst().orElseThrow();
        var response = controller.commit(preview.id(), new SubtitleCommitRequest(List.of(new Selection(selected.id(), null, null)), false)).data();
        assertThat(response.files()).extracting(file -> file.finalName()).containsExactly("Actual.zh-CN.srt");
        assertThat(response.sourceFileName()).isEqualTo("original.zip");
        verify(openList, never()).uploadFile(any(), anyString(), anyBoolean());
        executor.runNext();
        var local = org.mockito.ArgumentCaptor.forClass(Path.class);
        verify(openList).uploadFile(local.capture(), eq(MOVIE + "/Actual.zh-CN.srt"), eq(false));
        assertThat(Files.exists(local.getValue().getParent())).isFalse();
        verify(as).refreshForPath("MOVIE", MOVIE);
        assertThatThrownBy(() -> service.commit(preview.id(), new SubtitleCommitRequest(List.of(new Selection(selected.id(), null, null)), false)))
                .isInstanceOf(BusinessException.class);
        var saved = org.mockito.ArgumentCaptor.forClass(SubtitleUpload.class);
        verify(uploads).insert(saved.capture());
        when(uploads.selectById(response.id())).thenReturn(saved.getValue());
        remote.add(new OpenListFileInfo("Actual.zh-CN.srt", (long) SubtitleArchiveExtractorTest.SRT.length, false, null));
        assertThat(controller.check(response.id()).data().status()).isEqualTo("COMPLETE");
        assertThat(service.checkUpload(response.id()).output().status()).isEqualTo("UNAVAILABLE");
        remote.set(1, new OpenListFileInfo("Actual.zh-CN.srt", null, false, null));
        assertThat(service.checkUpload(response.id()).status()).isEqualTo("PENDING");
    }

    @Test
    void rejectsOwnerMismatchDuplicateForgedIdUnsafeVideoAndEmptySelection() {
        var preview = prepare();
        User other = new User(); other.setId(2L);
        when(auth.requireCurrentUser()).thenReturn(other);
        assertThatThrownBy(() -> service.commit(preview.id(), request("0", null, false))).isInstanceOf(BusinessException.class);
        when(auth.requireCurrentUser()).thenReturn(owner);
        assertThatThrownBy(() -> service.commit(preview.id(), new SubtitleCommitRequest(List.of(), false))).hasMessageContaining("至少");
        assertThatThrownBy(() -> service.commit(preview.id(), new SubtitleCommitRequest(List.of(new Selection("0", null, null), new Selection("0", null, null)), false)))
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> service.commit(preview.id(), request("../0", null, false))).hasMessageContaining("ID 无效");
        assertThatThrownBy(() -> service.commit(preview.id(), request("0", "../Actual.mkv", false))).hasMessageContaining("实际视频");
        verifyNoInteractions(uploads);
    }

    @Test
    void requiresExplicitVideoForMultipleMoviesAndAllowsLanguageOverride() {
        remote.add(video("Other.mkv", 900L));
        var preview = prepare();
        assertThat(preview.files().get(0).status()).isEqualTo("AMBIGUOUS");
        assertThatThrownBy(() -> service.commit(preview.id(), request("0", null, false))).hasMessageContaining("实际视频");
        var response = service.commit(preview.id(), new SubtitleCommitRequest(List.of(new Selection("0", "cht", "Actual.mkv")), false));
        assertThat(response.files().get(0).finalName()).isEqualTo("Actual.zh-TW.srt");
        executor.runNext();
    }

    @Test
    void duplicateEpisodeIsAmbiguousAndNumeric00StaysUnmatched() throws Exception {
        remote = new ArrayList<>(List.of(video("Show.S01E01.mkv", 100L), video("Show.S01E01.alt.mkv", 900L)));
        var preview = service.prepare(SubtitleArchiveExtractorTest.upload("season.zip", SubtitleArchiveExtractorTest.zip(
                Map.of("01.srt", SubtitleArchiveExtractorTest.SRT, "00.srt", SubtitleArchiveExtractorTest.SRT), StandardCharsets.UTF_8)),
                "series", "Show", null, null, 1, null, "unknown");
        assertThat(preview.files().stream().filter(file -> file.originalName().equals("01.srt")).findFirst().orElseThrow().status()).isEqualTo("AMBIGUOUS");
        var zero = preview.files().stream().filter(file -> file.originalName().equals("00.srt")).findFirst().orElseThrow();
        assertThat(zero.status()).isEqualTo("UNMATCHED");
        assertThat(zero.episodeNumber()).isNull();
        expire(preview.id());
    }

    @Test
    void stalePreviewCannotOverwriteNewFileEvenWithOverwriteTrue() {
        var preview = prepare();
        remote.add(new OpenListFileInfo("Actual.zh-CN.srt", 44L, false, null));
        assertConflict(() -> service.commit(preview.id(), request("0", null, true)));
        verifyNoInteractions(uploads);
    }

    @Test
    void videoChangeAndConcurrentReplayAreRejectedAndExpiryCleans() throws Exception {
        var preview = prepare();
        remote.set(0, video("Actual.mkv", 999L));
        assertConflict(() -> service.commit(preview.id(), request("0", null, false)));
        remote.set(0, video("Actual.mkv", 100L));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var calls = pool.invokeAll(List.<java.util.concurrent.Callable<Boolean>>of(() -> tryCommit(preview.id()), () -> tryCommit(preview.id())));
            assertThat(calls.stream().filter(call -> { try { return call.get(); } catch (Exception exception) { throw new RuntimeException(exception); } }).count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        executor.runNext();
        var expires = prepare();
        expire(expires.id());
        assertThatThrownBy(() -> service.commit(expires.id(), request("0", null, false))).hasMessageContaining("过期");
    }

    @Test
    void previewCommitRefusesOverwriteWhenRemoteVersionCannotBeVerified() {
        remote.add(new OpenListFileInfo("Actual.zh-CN.srt", 44L, false, null));
        var preview = prepare();
        assertThat(preview.files().get(0).conflictReason()).isEqualTo("REMOTE_EXISTS");
        assertConflict(() -> service.commit(preview.id(), request("0", null, false)));
        assertConflict(() -> service.commit(preview.id(), request("0", null, true)));
        verify(openList, never()).uploadFile(any(), anyString(), anyBoolean());
        verify(openList, never()).remove(anyString(), anyList());
    }

    @Test
    void sameTargetNamesCannotBeCommittedTogetherAndCapacityIsBounded() throws Exception {
        var preview = service.prepare(SubtitleArchiveExtractorTest.upload("duplicates.zip", SubtitleArchiveExtractorTest.zip(
                Map.of("a.chs.srt", SubtitleArchiveExtractorTest.SRT, "b.chs.srt", SubtitleArchiveExtractorTest.SRT), StandardCharsets.UTF_8)),
                "movie", "Movie", null, 2024, null, null, "unknown");
        assertThat(preview.files()).allMatch(file -> file.status().equals("CONFLICT"));
        assertThatThrownBy(() -> service.commit(preview.id(), new SubtitleCommitRequest(
                List.of(new Selection("0", null, null), new Selection("1", null, null)), false))).hasMessageContaining("重复");
        assertThat(preview.files()).allMatch(file -> "DUPLICATE_CANDIDATE".equals(file.conflictReason()));
        service.commit(preview.id(), request("0", null, false));
        executor.runNext();
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 8; index++) ids.add(prepare().id());
        assertConflict(this::prepare);
        for (String id : ids) expire(id);
    }
    @Test
    void legacyUploadDoesNotAutoMatchZeroButAcceptsNumericPositiveEpisode() {
        remote = new ArrayList<>(List.of(video("Show.S01E01.mkv", 100L)));
        when(openList.listFiles(anyString())).thenAnswer(call -> List.copyOf(remote));
        service.uploadSubtitle(SubtitleArchiveExtractorTest.upload("00.chs.srt", SubtitleArchiveExtractorTest.SRT),
                "series", "Show", null, null, 1, false, null);
        executor.runNext();
        verify(openList, never()).uploadFile(any(), anyString(), anyBoolean());
        service.uploadSubtitle(SubtitleArchiveExtractorTest.upload("01.chs.srt", SubtitleArchiveExtractorTest.SRT),
                "series", "Show", null, null, 1, false, null);
        executor.runNext();
        verify(openList).uploadFile(any(), eq("/tv/Show/Season 01/Show.S01E01.zh-CN.srt"), eq(false));
    }


    private com.medianexus.orchestrator.dto.subtitle.response.SubtitlePreparationResponse prepare() {
        return service.prepare(SubtitleArchiveExtractorTest.upload("movie.chs.srt", SubtitleArchiveExtractorTest.SRT),
                "movie", "Movie", null, 2024, null, null, "unknown");
    }

    private boolean tryCommit(String id) {
        try { service.commit(id, request("0", null, false)); return true; }
        catch (BusinessException exception) { return false; }
    }

    private void expire(String id) throws Exception {
        var field = SubtitleUploadService.class.getDeclaredField("preparations");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) field.get(service);
        Object preparation = map.get(id);
        var components = preparation.getClass().getRecordComponents();
        Object[] arguments = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        Path directory = null;
        for (int index = 0; index < components.length; index++) {
            var accessor = components[index].getAccessor(); accessor.setAccessible(true);
            arguments[index] = accessor.invoke(preparation);
            types[index] = components[index].getType();
            if (components[index].getName().equals("expiresAt")) arguments[index] = Instant.EPOCH;
            if (arguments[index] instanceof SubtitleArchiveExtractor.ExtractedSubtitlePackage extracted) directory = extracted.workDir();
        }
        var constructor = preparation.getClass().getDeclaredConstructor(types); constructor.setAccessible(true);
        map.put(id, constructor.newInstance(arguments));
        service.expirePreparations();
        assertThat(map).doesNotContainKey(id);
        assertThat(Files.exists(directory)).isFalse();
    }

    private SubtitleCommitRequest request(String id, String video, boolean overwrite) {
        return new SubtitleCommitRequest(List.of(new Selection(id, null, video)), overwrite);
    }
    private OpenListFileInfo video(String name, long size) { return new OpenListFileInfo(name, size, false, null); }
    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
    static class QueueExecutor extends AbstractExecutorService {
        final List<Runnable> commands = new ArrayList<>();
        public void execute(Runnable command) { commands.add(command); }
        void runNext() { commands.remove(0).run(); }
        public void shutdown() { }
        public List<Runnable> shutdownNow() { return List.of(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
    }
}
