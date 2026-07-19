package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadResponse;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.mapper.SubtitleUploadLogMapper;
import com.medianexus.orchestrator.mapper.SubtitleUploadMapper;
import com.medianexus.orchestrator.model.SubtitleUpload;
import com.medianexus.orchestrator.model.SubtitleUploadLog;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.AutoSymlinkRefreshService.RefreshOutcome;
import com.medianexus.orchestrator.service.SubtitleArchiveExtractor.ExtractedSubtitleEntry;
import com.medianexus.orchestrator.service.SubtitleArchiveExtractor.ExtractedSubtitlePackage;
import com.medianexus.orchestrator.service.SubtitleArchiveExtractor.StagedSubtitleUpload;
import com.medianexus.orchestrator.service.SubtitleFilenamePlanner.PlannedSubtitleFile;
import com.medianexus.orchestrator.service.SubtitleFilenamePlanner.SubtitleFilenamePlan;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class SubtitleUploadServiceTest {

    private final SubtitleUploadMapper uploadMapper = mock(SubtitleUploadMapper.class);
    private final SubtitleUploadLogMapper uploadLogMapper = mock(SubtitleUploadLogMapper.class);
    private final OpenListClient openListClient = mock(OpenListClient.class);
    private final OpenListProperties openListProperties = new OpenListProperties();
    private final MovieSeriesFileRenameService renameService = mock(MovieSeriesFileRenameService.class);
    private final TestAuthService authService = new TestAuthService();
    private final SubtitleArchiveExtractor archiveExtractor = mock(SubtitleArchiveExtractor.class);
    private final SubtitleFilenamePlanner filenamePlanner = mock(SubtitleFilenamePlanner.class);
    private final AutoSymlinkRefreshService autoSymlinkRefreshService = mock(AutoSymlinkRefreshService.class);
    private final ControlledExecutor executor = new ControlledExecutor();
    private final List<SubtitleUploadLog> logs = new ArrayList<>();
    private final StagedSubtitleUpload stagedUpload = new StagedSubtitleUpload(
            Path.of("/tmp/subtitle-upload"),
            Path.of("/tmp/subtitle-upload/source-upload.bin"),
            "Movie.ass",
            12L,
            "source-sha"
    );
    private final SubtitleUploadService service = new SubtitleUploadService(
            uploadMapper,
            uploadLogMapper,
            openListClient,
            openListProperties,
            renameService,
            authService,
            archiveExtractor,
            filenamePlanner,
            new ObjectMapper(),
            autoSymlinkRefreshService,
            executor
    );

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SubtitleUpload.class
        );
        User user = new User();
        user.setId(1L);
        user.setRole("USER");
        authService.currentUser = user;
        openListProperties.setMovieRootPath("/movies");
        when(openListClient.normalizePath("/movies")).thenReturn("/movies");
        when(openListClient.joinPath(anyString(), anyString())).thenAnswer(invocation ->
                invocation.getArgument(0) + "/" + invocation.getArgument(1));
        when(renameService.movieFolderName("Movie", 2024)).thenReturn("Movie (2024)");
        when(archiveExtractor.stage(any())).thenReturn(stagedUpload);
        when(uploadMapper.insert(any(SubtitleUpload.class))).thenReturn(1);
        when(uploadLogMapper.insert(any(SubtitleUploadLog.class))).thenAnswer(invocation -> {
            logs.add(invocation.getArgument(0));
            return 1;
        });
    }

    @Test
    void returnsCreatedBatchBeforeOpenListProcessingStarts() {
        SubtitleUploadResponse response = uploadMovieSubtitle();

        assertThat(response.status()).isEqualTo("PROCESSING");
        assertThat(response.stage()).isEqualTo("created");
        assertThat(executor.pendingCount()).isEqualTo(1);
        assertThat(logs).extracting(SubtitleUploadLog::getMessage)
                .containsExactly("已创建电影字幕上传批次");
        verify(openListClient, never()).pathExists(anyString());
        verify(autoSymlinkRefreshService, never()).refreshMovie();
    }

    @Test
    void backgroundProcessingUploadsSubtitleThenRefreshesMovieAutoSymlink() {
        ExtractedSubtitleEntry entry = new ExtractedSubtitleEntry(
                "Movie.ass",
                "Movie.ass",
                "ass",
                12L,
                "entry-sha",
                Path.of("/tmp/subtitle-upload/source-upload.bin")
        );
        when(archiveExtractor.extract(stagedUpload)).thenReturn(new ExtractedSubtitlePackage(
                stagedUpload.workDir(),
                stagedUpload.sourceFileName(),
                false,
                stagedUpload.sourceSize(),
                stagedUpload.sourceSha256(),
                List.of(entry),
                List.of()
        ));
        when(openListClient.pathExists("/movies/Movie (2024)")).thenReturn(true);
        when(openListClient.listFiles("/movies/Movie (2024)")).thenReturn(List.of(
                new OpenListFileInfo("Movie.mkv", 1024L, false, "/movies/Movie (2024)")
        ));
        when(renameService.isVideo("Movie.mkv")).thenReturn(true);
        when(filenamePlanner.plan("Movie.mkv", List.of(entry))).thenReturn(new SubtitleFilenamePlan(
                "Movie",
                "",
                List.of(new PlannedSubtitleFile(entry, "Movie.ass"))
        ));
        when(autoSymlinkRefreshService.refreshMovie()).thenReturn(RefreshOutcome.submitted("task=movie, attempt=1"));

        uploadMovieSubtitle();
        executor.runNext();

        verify(openListClient).uploadFile(
                eq(Path.of("/tmp/subtitle-upload/source-upload.bin")),
                eq("/movies/Movie (2024)/Movie.ass"),
                eq(false)
        );
        verify(autoSymlinkRefreshService).refreshMovie();
        assertThat(logs).extracting(SubtitleUploadLog::getMessage)
                .containsSubsequence(
                        "字幕文件上传完成",
                        "正在触发 AutoSymlink 刷新",
                        "AutoSymlink 刷新任务已提交",
                        "字幕已写入目标目录，等待 AS 后续迁移"
                );
    }

    @Test
    void backgroundProcessingRefreshesSeriesAutoSymlinkForSeriesSubtitle() {
        StagedSubtitleUpload seriesUpload = new StagedSubtitleUpload(
                Path.of("/tmp/series-subtitle-upload"),
                Path.of("/tmp/series-subtitle-upload/source-upload.bin"),
                "Show.ass",
                12L,
                "series-source-sha"
        );
        ExtractedSubtitleEntry entry = new ExtractedSubtitleEntry(
                "Show.ass",
                "Show.ass",
                "ass",
                12L,
                "series-entry-sha",
                seriesUpload.sourcePath()
        );
        openListProperties.setTvRootPath("/tv");
        when(openListClient.normalizePath("/tv")).thenReturn("/tv");
        when(renameService.seriesFolderName("Show")).thenReturn("Show");
        when(renameService.seasonFolderName(1)).thenReturn("Season 1");
        when(archiveExtractor.stage(any())).thenReturn(seriesUpload);
        when(archiveExtractor.extract(seriesUpload)).thenReturn(new ExtractedSubtitlePackage(
                seriesUpload.workDir(),
                seriesUpload.sourceFileName(),
                false,
                seriesUpload.sourceSize(),
                seriesUpload.sourceSha256(),
                List.of(entry),
                List.of()
        ));
        when(openListClient.pathExists("/tv/Show/Season 1")).thenReturn(true);
        when(openListClient.listFiles("/tv/Show/Season 1")).thenReturn(List.of(
                new OpenListFileInfo("Show S01E01.mkv", 1024L, false, "/tv/Show/Season 1")
        ));
        when(renameService.isVideo("Show S01E01.mkv")).thenReturn(true);
        when(renameService.episodeNumber("Show S01E01.mkv", 1)).thenReturn(Optional.of(1));
        when(renameService.episodeNumber("Show.ass", 1)).thenReturn(Optional.empty());
        when(filenamePlanner.plan("Show S01E01.mkv", List.of(entry))).thenReturn(new SubtitleFilenamePlan(
                "Show S01E01",
                "",
                List.of(new PlannedSubtitleFile(entry, "Show S01E01.ass"))
        ));
        when(autoSymlinkRefreshService.refreshSeries()).thenReturn(
                RefreshOutcome.submitted("task=tv, attempt=1")
        );

        service.uploadSubtitle(
                new MockMultipartFile("file", "Show.ass", "text/plain", "subtitle".getBytes()),
                "series",
                "Show",
                null,
                2024,
                1,
                false
        );
        executor.runNext();

        verify(autoSymlinkRefreshService).refreshSeries();
        verify(autoSymlinkRefreshService, never()).refreshMovie();
    }

    private SubtitleUploadResponse uploadMovieSubtitle() {
        return service.uploadSubtitle(
                new MockMultipartFile("file", "Movie.ass", "text/plain", "subtitle".getBytes()),
                "movie",
                "Movie",
                null,
                2024,
                null,
                false
        );
    }

    private static class TestAuthService extends AuthService {

        private User currentUser;

        TestAuthService() {
            super(null, (RegistrationCodeSettingsService) null, null);
        }

        @Override
        public User requireCurrentUser() {
            return currentUser;
        }
    }

    private static class ControlledExecutor extends AbstractExecutorService {

        private final List<Runnable> commands = new ArrayList<>();

        int pendingCount() {
            return commands.size();
        }

        void runNext() {
            commands.remove(0).run();
        }

        @Override
        public void execute(Runnable command) {
            commands.add(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }
}
