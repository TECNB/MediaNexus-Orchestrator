package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListLibraryOrganizer;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationProgressObserver;
import com.medianexus.orchestrator.service.organization.LibraryOrganizer;
import com.medianexus.orchestrator.support.IngestTargetFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class MagnetIngestServiceTest {

    @Test void refusedAdmissionReturnsConflictAndDiscardsOnlyNewPrewriteClaim() {
        var client = mock(OpenListClient.class); stubPathHelpers(client);
        var organizer = mock(com.medianexus.orchestrator.integration.clouddrive.CloudDrive2LibraryOrganizer.class);
        var service = service(client, organizer);
        var task = movieTask("/movie"); task.setStatus("FAILED");
        var tasks = mock(MovieMagnetIngestTaskMapper.class); when(tasks.selectById(task.getId())).thenReturn(task);
        var logs = mock(MovieMagnetIngestTaskLogMapper.class); when(logs.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        var journal = mock(OrganizationRecoveryJournal.class); var scheduler = mock(OfflineIngestScheduler.class);
        ReflectionTestUtils.setField(service, "movieTaskMapper", tasks); ReflectionTestUtils.setField(service, "movieTaskLogMapper", logs);
        ReflectionTestUtils.setField(service, "organizationJournal", journal); ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
        org.mockito.Mockito.doAnswer(call -> { ((Runnable) call.getArgument(4)).run(); return null; }).when(scheduler)
                .enqueueOrganizationRecovery(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> service.recoverOrganization("movie", task.getId())).hasMessageContaining("校验未通过");
        verify(scheduler).refuseOrganizationAdmission(eq("movie:" + task.getId()), eq("/movie"), org.mockito.ArgumentMatchers.isNull());
        verify(journal, never()).organize(anyString(), anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(organizer);
    }

    @Test void recoveryHttpAndLogExposeSanitizedSafetyReasonFromExactOriginalTaskEvidence() {
        var client = mock(OpenListClient.class); stubPathHelpers(client);
        var organizer = mock(com.medianexus.orchestrator.integration.clouddrive.CloudDrive2LibraryOrganizer.class);
        var service = service(client, organizer);
        var task = movieTask("/movie/Title"); task.setStatus("FAILED"); task.setMagnet("magnet:?xt=urn:btih:" + "a".repeat(40)); task.setMagnetHash("a".repeat(40));
        var tasks = mock(MovieMagnetIngestTaskMapper.class); when(tasks.selectById(task.getId())).thenReturn(task);
        var logs = mock(MovieMagnetIngestTaskLogMapper.class); when(logs.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        var original = new com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog();
        original.setTaskId(task.getId()); original.setStage("submission_safety"); original.setDetail("EXACT_ORIGINAL_RECORD");
        when(logs.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(original));
        var registry = mock(com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry.class);
        when(registry.bindingForPath("MOVIE", task.getSavePath())).thenReturn(java.util.Optional.of(
                new com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry.CategoryBinding("id", "B", "MOVIE", "/movie", "as", "{}", "/strm", "library")));
        var safety = mock(ReleaseSubmissionSafety.class);
        org.mockito.Mockito.doThrow(new com.medianexus.orchestrator.common.exception.BusinessException(
                com.medianexus.orchestrator.common.exception.ErrorCode.BAD_REQUEST,
                "RECOVERY_ORIGINAL_TABLE_MALFORMED https://dl.mypikpak.net/x?sign=SECRET token=HIDDEN",
                org.springframework.http.HttpStatus.BAD_REQUEST)).when(safety).checkMovieOrganizationRecovery(
                org.mockito.ArgumentMatchers.isNull(), eq(task.getMagnet()), eq(task.getMagnetHash()), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), eq("EXACT_ORIGINAL_RECORD"));
        var journal = mock(OrganizationRecoveryJournal.class); var scheduler = mock(OfflineIngestScheduler.class);
        ReflectionTestUtils.setField(service, "movieTaskMapper", tasks); ReflectionTestUtils.setField(service, "movieTaskLogMapper", logs);
        ReflectionTestUtils.setField(service, "targetRegistry", registry); ReflectionTestUtils.setField(service, "submissionSafety", safety);
        ReflectionTestUtils.setField(service, "organizationJournal", journal); ReflectionTestUtils.setField(service, "offlineScheduler", scheduler);
        org.mockito.Mockito.doAnswer(call -> { ((Runnable) call.getArgument(4)).run(); return null; }).when(scheduler)
                .enqueueOrganizationRecovery(anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        assertThatThrownBy(() -> service.recoverOrganization("movie", task.getId()))
                .hasMessageContaining("SAFETY_REJECTED").hasMessageContaining("RECOVERY_ORIGINAL_TABLE_MALFORMED")
                .hasMessageNotContaining("SECRET").hasMessageNotContaining("HIDDEN").hasMessageNotContaining("https://");
        var captured = ArgumentCaptor.forClass(com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog.class);
        verify(logs).insert(captured.capture());
        assertThat(captured.getValue().getDetail()).contains("RECOVERY_ORIGINAL_TABLE_MALFORMED").doesNotContain("SECRET", "HIDDEN", "https://");
        org.mockito.Mockito.verifyNoInteractions(organizer);
        assertThat(task.getStatus()).isEqualTo("FAILED");
    }

    @Test void legacyMovieSameBytesUnrelatedNameIsRejected() {
        var client = mock(OpenListClient.class); stubPathHelpers(client);
        var task = movieTask("/movie");
        when(client.findFiles("/movie", true)).thenReturn(List.of(new OpenListFileInfo("unrelated.mp4", 3452436947L, false, "/movie")));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service(client), "requireLegacyMovieCompleteness", task,
                "original_file=release/Furious.7.2015.1080p.mp4; bytes=3452436947; preview=false"))
                .hasMessageContaining("文件");
    }

    @Test void legacyOriginalMovieAndThirtyCanonicalEpisodesHaveUniqueReleaseCorrespondence() {
        var client = mock(OpenListClient.class); stubPathHelpers(client);
        var movie = movieTask("/movie"); movie.setTitle("洛杉矶劫案"); movie.setOriginalTitle("Crime 101"); movie.setYear(2026);
        String original = "洛杉矶劫案.1080p.HD中英双字[最新电影www.5266ys.com].mp4";
        when(client.findFiles("/movie", true)).thenReturn(List.of(new OpenListFileInfo(original, 3452436947L, false, "/movie")));
        ReflectionTestUtils.invokeMethod(service(client), "requireLegacyMovieCompleteness", movie,
                "original_file=release/" + original + "; bytes=3452436947; preview=false");
        var series = animeSeriesTask(); series.setTitle("师兄太稳健"); series.setOriginalTitle("师兄太稳健"); series.setSeasonNumber(1);
        List<OpenListFileInfo> current = new ArrayList<>(); StringBuilder evidence = new StringBuilder();
        for (int episode = 1; episode <= 30; episode++) {
            evidence.append("original_file=release/师兄太稳健.S01E%02d.1080p.mkv; bytes=%d; preview=false\n".formatted(episode, 900000000L + episode));
            current.add(new OpenListFileInfo("师兄太稳健 - S01E%02d 1080p.mkv".formatted(episode), 900000000L + episode, false,
                    series.getTempPath() + (java.util.Set.of(8,12,16,23).contains(episode) ? "/nested" : "")));
        }
        when(client.findFiles(series.getTempPath(), true)).thenReturn(current);
        ReflectionTestUtils.invokeMethod(service(client), "requireLegacySeriesCompleteness", series, evidence.toString());
    }

    @Test void ambiguousReleaseCanonicalNamesCannotAdoptEvenWithDifferentBytes() {
        var client = mock(OpenListClient.class); stubPathHelpers(client);
        var task = animeSeriesTask();
        when(client.findFiles(task.getTempPath(), true)).thenReturn(List.of(
                new OpenListFileInfo("Show - S02E01 1080p.mp4", 100L, false, task.getTempPath()),
                new OpenListFileInfo("Show.S02E01.1080p.mp4", 200L, false, task.getTempPath())));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service(client), "requireLegacySeriesCompleteness", task,
                "original_file=a/Show.S02E01.1080p.mp4; bytes=100; preview=false\noriginal_file=b/Show.S02E01.1080p.mp4; bytes=200; preview=false"))
                .hasMessageContaining("文件");
    }

    @Test void durableBusinessPlanCountsThirtyWithOnlyFourMovesAndPreservesFailure() {
        OpenListClient client = mock(OpenListClient.class); stubPathHelpers(client);
        var task = animeSeriesTask(); task.setTaskProductType("SERIES"); task.setTmdbId(272938); task.setStatus("FAILED");
        List<OpenListFileInfo> files = new ArrayList<>();
        for (int episode = 1; episode <= 30; episode++) files.add(new OpenListFileInfo("Show - S02E%02d.mp4".formatted(episode),
                900_000_000L + episode, false, task.getSavePath() + (java.util.Set.of(8, 12, 16, 23).contains(episode) ? "/nested" : "")));
        when(client.findFiles(task.getTempPath())).thenReturn(files);
        when(client.listFiles(task.getSavePath())).thenReturn(files.stream().filter(f -> f.path().equals(task.getSavePath())).toList());
        var organizer = mock(com.medianexus.orchestrator.integration.clouddrive.CloudDrive2LibraryOrganizer.class);
        var journal = mock(OrganizationRecoveryJournal.class);
        var service = service(client, organizer);
        ReflectionTestUtils.setField(service, "organizationJournal", journal);
        var tasks = mock(SeriesMagnetIngestTaskMapper.class);
        when(tasks.selectById(task.getId())).thenReturn(task);
        ReflectionTestUtils.setField(service, "seriesTaskMapper", tasks);
        Object result = ReflectionTestUtils.invokeMethod(service, "organizeSeriesFiles", task);
        var plan = ArgumentCaptor.forClass(LibraryOrganizationPlan.class);
        verify(journal).organize(eq("series"), eq(task.getId()), plan.capture(), eq(30), eq(0), eq(30), eq(272938), eq(2),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any());
        assertThat(plan.getValue().expectedTargetNames()).hasSize(30);
        assertThat(plan.getValue().moves()).hasSize(4);
        assertThat(plan.getValue().renames()).isEmpty();
        assertThat(ReflectionTestUtils.getField(result, "videoCount")).isEqualTo(30);
        assertThat(task.getStatus()).isEqualTo("FAILED");
        verify(tasks, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test void twoIdenticalTwentyThreeEpisodeDirectoriesBlockBeforeJournalOrWrites() {
        OpenListClient client = mock(OpenListClient.class); stubPathHelpers(client);
        var task = animeSeriesTask(); task.setTaskProductType("SERIES");
        List<OpenListFileInfo> files = new ArrayList<>();
        for (String dir : List.of("copy-one", "copy-two")) for (int episode = 1; episode <= 23; episode++)
            files.add(new OpenListFileInfo("Show S02E%02d.mp4".formatted(episode), 900_000_000L + episode, false, task.getSavePath() + "/" + dir));
        when(client.findFiles(task.getTempPath())).thenReturn(files);
        var organizer = mock(com.medianexus.orchestrator.integration.clouddrive.CloudDrive2LibraryOrganizer.class);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task))
                .hasMessageContaining("目标文件名冲突");
        org.mockito.Mockito.verifyNoInteractions(organizer);
    }

    @Test
    void animeUnknownRootVideoCannotBypassSafetyThroughDirectorySelection() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        when(client.listFiles(task.getTempPath())).thenReturn(List.of(
                new OpenListFileInfo("unrecognized.mp4", 900_000_000L, false, task.getTempPath()),
                new OpenListFileInfo("release", null, true, task.getTempPath())));
        when(client.listFiles(task.getTempPath() + "/release")).thenReturn(List.of(
                new OpenListFileInfo("Show S02E01.mp4", 900_000_000L, false, task.getTempPath() + "/release")));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task))
                .hasMessageContaining("无法识别集数").hasMessageContaining("保留源文件");
        assertThat(organizer.plan).isNull();
    }

    @Test
    void seriesUnknownVideoStopsBeforeAnyOrganization() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTaskProductType("SERIES");
        when(client.findFiles(task.getTempPath())).thenReturn(List.of(
                new OpenListFileInfo("unrecognized.mp4", 900_000_000L, false, task.getTempPath())));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task))
                .hasMessageContaining("无法识别集数").hasMessageContaining("保留源文件");
        assertThat(organizer.plan).isNull();
        verify(client, never()).remove(anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test void seriesSkipsOnlyStandalonePromotionalVideoEvenWhenPaddedBeforeOrganization() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTaskProductType("SERIES");
        when(client.findFiles(task.getTempPath())).thenReturn(List.of(
                new OpenListFileInfo("01.1080p.HD.mkv", 900_000_000L, false, task.getTempPath()),
                new OpenListFileInfo("【更多无水印高品质资源请访问 www.Butailing.com】.MKV", 500L * 1024 * 1024, false, task.getTempPath())));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();
        ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task);
        assertThat(organizer.plan.expectedTargetNames()).hasSize(1);
        assertThat(organizer.plan.deletions()).hasSize(1);
    }

    @Test
    void seriesDuplicateVideoTargetsStopBeforeAnyOrganization() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTaskProductType("SERIES");
        when(client.findFiles(task.getTempPath())).thenReturn(List.of(
                new OpenListFileInfo("Show S02E01.mp4", 900_000_000L, false, task.getTempPath() + "/1080P.HBR"),
                new OpenListFileInfo("Show S02E01.mp4", 1_800_000_000L, false, task.getTempPath() + "/4K")));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task))
                .hasMessageContaining("目标文件名冲突").hasMessageContaining("保留源文件");
        assertThat(organizer.plan).isNull();
    }

    @Test
    void seriesTitleSuffixEpisodesProduceTwelveUniqueTargetsWithoutVideoDeletion() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTaskProductType("SERIES");
        task.setTitle("隐秘的角落");
        task.setOriginalTitle("The Bad Kids");
        task.setSeasonNumber(1);
        List<OpenListFileInfo> files = new ArrayList<>();
        for (int episode = 1; episode <= 12; episode++) {
            files.add(new OpenListFileInfo("隐秘的角落%02d.mp4".formatted(episode), 900_000_000L, false,
                    task.getTempPath() + "/1080P.HBR"));
        }
        when(client.findFiles(task.getTempPath())).thenReturn(files);
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task);

        assertThat(organizer.plan.expectedTargetNames()).hasSize(12)
                .contains("The Bad Kids - S01E01.mp4", "The Bad Kids - S01E12.mp4");
        assertThat(organizer.plan.deletions()).isEmpty();
    }

    @Test
    void realMixedPreviewPackPlansAllTwelveEpisodesAndPreservesEveryVersion() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTaskProductType("SERIES");
        task.setTitle("隐秘的角落");
        task.setOriginalTitle("隐秘的角落");
        task.setSeasonNumber(1);
        List<OpenListFileInfo> files = new ArrayList<>();
        List<String> expectedNames = new ArrayList<>();
        for (int episode = 1; episode <= 12; episode++) {
            String suffix = episode <= 6 ? "第" + episode + "集-蓝光1080P"
                    : "尝鲜版%02d%s".formatted(episode, episode == 12 ? "End" : "");
            files.add(new OpenListFileInfo("【大陆剧网www.daluju123.com】隐秘的角落" + suffix + ".mp4",
                    200_000_000L, false, task.getTempPath() + "/Release"));
            expectedNames.add("隐秘的角落 - S01E%02d%s.mp4".formatted(episode,
                    episode <= 6 ? " 1080p" : " 尝鲜版"));
        }
        when(client.findFiles(task.getTempPath())).thenReturn(files);
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        Object result = ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task);

        assertThat(ReflectionTestUtils.getField(result, "videoCount")).isEqualTo(12);
        assertThat(ReflectionTestUtils.getField(result, "organizedCount")).isEqualTo(12);
        assertThat(organizer.plan.expectedTargetNames()).containsExactlyInAnyOrderElementsOf(expectedNames);
        assertThat(organizer.plan.renames()).hasSize(12);
        assertThat(organizer.plan.moves()).hasSize(12);
        assertThat(organizer.plan.deletions()).isEmpty();
    }

    @Test
    void seriesExistingDestinationStopsWithoutDeletingIncomingVideo() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTaskProductType("SERIES");
        when(client.findFiles(task.getTempPath())).thenReturn(List.of(
                new OpenListFileInfo("Show S02E01.mp4", 900_000_000L, false, task.getTempPath() + "/release")));
        when(client.listFiles(task.getSavePath())).thenReturn(List.of(
                new OpenListFileInfo("Show - S02E01.mp4", 900_000_000L, false, task.getSavePath())));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task))
                .hasMessageContaining("目标文件名冲突");
        assertThat(organizer.plan).isNull();
    }

    @Test
    void seriesWithoutFeatureVideoDoesNotExecuteAuxiliaryDeletion() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTaskProductType("SERIES");
        when(client.findFiles(task.getTempPath())).thenReturn(List.of(
                new OpenListFileInfo("Show NCOP.mp4", 90_000_000L, false, task.getTempPath())));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task))
                .hasMessageContaining("没有识别到可入库的视频文件").hasMessageContaining("保留源文件");
        assertThat(organizer.plan).isNull();
    }

    @Test
    void seriesAlreadyOrganizedVideoCanBeProcessedWithoutDeletion() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTaskProductType("SERIES");
        var existing = new OpenListFileInfo("Show - S02E01.mp4", 900_000_000L, false, task.getSavePath());
        when(client.findFiles(task.getTempPath())).thenReturn(List.of(existing));
        when(client.listFiles(task.getSavePath())).thenReturn(List.of(existing));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task);

        assertThat(organizer.plan.expectedTargetNames()).containsExactly("Show - S02E01.mp4");
        assertThat(organizer.plan.renames()).isEmpty();
        assertThat(organizer.plan.deletions()).isEmpty();
    }

    @Test
    void recentMovieAndSeriesQueriesFilterManualSourcesBeforeLimit() {
        initTableInfo(MovieMagnetIngestTask.class);
        initTableInfo(SeriesMagnetIngestTask.class);
        MovieMagnetIngestTaskMapper movieMapper = mock(MovieMagnetIngestTaskMapper.class);
        SeriesMagnetIngestTaskMapper seriesMapper = mock(SeriesMagnetIngestTaskMapper.class);
        AuthService authService = mock(AuthService.class);
        User admin = new User();
        admin.setRole("ADMIN");
        when(authService.requireCurrentUser()).thenReturn(admin);
        MagnetIngestService service = new MagnetIngestService(
                movieMapper,
                mock(MovieMagnetIngestTaskLogMapper.class),
                seriesMapper,
                mock(SeriesMagnetIngestTaskLogMapper.class),
                mock(OpenListClient.class),
                new OpenListProperties(),
                new MovieSeriesFileRenameService(),
                mock(LibraryOrganizer.class),
                authService,
                null,
                null,
                null,
                IngestTargetFixtures.legacy(),
                mock(IngestTaskNotifier.class)
        );

        service.listMovieTasks();
        service.listSeriesTasks();

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> movieQuery =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper> seriesQuery =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(movieMapper).selectList(movieQuery.capture());
        verify(seriesMapper).selectList(seriesQuery.capture());
        assertThat(movieQuery.getValue().getSqlSegment())
                .contains("source_type", "IS NULL", "ORDER BY created_at DESC", "LIMIT 20");
        assertThat(seriesQuery.getValue().getSqlSegment())
                .contains("source_type", "IS NULL", "ORDER BY created_at DESC", "LIMIT 20");
    }

    private void initTableInfo(Class<?> entityType) {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(),
                        ""
                ),
                entityType
        );
    }

    @Test
    void seriesRetainsExplicitQualityConflictsAndForeignSubtitlesInChineseDirectory() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTaskProductType("TV");
        String root = task.getSavePath();
        when(client.findFiles(root)).thenReturn(List.of(
                new OpenListFileInfo("Show.S02E01.1080p.mkv", 300L * 1024 * 1024, false, root),
                new OpenListFileInfo("Show.S02E01.2160p.chs.srt", 100L, false, root + "/subs"),
                new OpenListFileInfo("Show.S02E01.尝鲜版.chs.srt", 100L, false, root + "/subs"),
                new OpenListFileInfo("Show.S02E01.jpn.srt", 100L, false, root + "/简体")));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task);

        assertThat(organizer.plan.renames()).hasSize(1);
        assertThat(organizer.plan.deletions()).isEmpty();
        assertThat(organizer.plan.cleanupExcludedDirectories()).contains(root + "/subs", root + "/简体");
    }

    @Test
    void movieRetainsSubtitleWithExplicitlyConflictingVideoQuality() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        String root = "/movies/Furious 7 (2015)";
        when(client.findFiles(root)).thenReturn(List.of(
                new OpenListFileInfo("release.1080p.mkv", 300L * 1024 * 1024, false, root),
                new OpenListFileInfo("release.2160p.chs.srt", 100L, false, root + "/subs")));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeMovieFiles", movieTask(root));

        assertThat(organizer.plan.renames()).hasSize(1);
        assertThat(organizer.plan.deletions()).isEmpty();
    }

    @Test
    void movieKeepsSourceDirectoryCollisionsAndPairedImageSubtitles() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        String root = "/movies/Furious 7 (2015)";
        List<OpenListFileInfo> files = List.of(
                new OpenListFileInfo("release.mkv", 300L * 1024 * 1024, false, root),
                new OpenListFileInfo("one.chs.srt", 100L, false, root + "/subs"),
                new OpenListFileInfo("Furious 7 (2015).zh-CN.srt", 100L, false, root + "/subs"),
                new OpenListFileInfo("one.chs.idx", 100L, false, root + "/subs"),
                new OpenListFileInfo("one.chs.sub", 100L, false, root + "/subs"));
        when(client.findFiles(root)).thenReturn(files);
        when(client.listFiles(root + "/subs")).thenReturn(files.subList(1, files.size()));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeMovieFiles", movieTask(root));

        assertThat(organizer.plan.deletions()).isEmpty();
        assertThat(organizer.plan.renames()).hasSize(1);
        assertThat(organizer.plan.cleanupExcludedDirectories()).contains(root + "/subs");
    }

    @Test
    void movieDoesNotGuessVideoVersionForChineseSubtitle() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        String root = "/movies/Furious 7 (2015)";
        when(client.findFiles(root)).thenReturn(List.of(
                new OpenListFileInfo("release.1080p.mkv", 300L * 1024 * 1024, false, root),
                new OpenListFileInfo("release.2160p.mkv", 400L * 1024 * 1024, false, root),
                new OpenListFileInfo("one.chs.srt", 100L, false, root + "/subs")
        ));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeMovieFiles", movieTask(root));

        assertThat(organizer.plan.renames()).hasSize(2);
        assertThat(organizer.plan.deletions()).isEmpty();
        assertThat(organizer.plan.cleanupExcludedDirectories()).contains(root + "/subs");
    }

    @Test
    void movieNormalizesOnlyClearChineseAndPreservesVariantsAndCollisions() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        String root = "/movies/Furious 7 (2015)";
        when(client.findFiles(root)).thenReturn(List.of(
                new OpenListFileInfo("release.mkv", 300L * 1024 * 1024, false, root),
                new OpenListFileInfo("one.chs.srt", 100L, false, root + "/a"),
                new OpenListFileInfo("two.chs.srt", 100L, false, root + "/b"),
                new OpenListFileInfo("one.cht.srt", 100L, false, root + "/a"),
                new OpenListFileInfo("one.chi.srt", 100L, false, root + "/a"),
                new OpenListFileInfo("one.chs.forced.srt", 100L, false, root + "/a"),
                new OpenListFileInfo("one.chs.cht.srt", 100L, false, root + "/a"),
                new OpenListFileInfo("one.中英.srt", 100L, false, root + "/a")
        ));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeMovieFiles", movieTask(root));

        assertThat(organizer.plan.deletions()).isEmpty();
        assertThat(organizer.plan.renames()).extracting(LibraryOrganizationPlan.RenameOperation::targetName)
                .containsExactlyInAnyOrder("Furious 7 (2015).mkv", "Furious 7 (2015).zh-CN.srt",
                        "Furious 7 (2015).zh-TW.srt", "Furious 7 (2015).zh.srt");
        assertThat(organizer.plan.cleanupExcludedDirectories()).contains(root + "/a", root + "/b");
    }

    @Test
    void seriesRetainsSubtitlesWithoutEpisodeOrMatchingVideo() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTaskProductType("TV");
        String root = task.getSavePath();
        when(client.findFiles(root)).thenReturn(List.of(
                new OpenListFileInfo("Show.S02E01.mkv", 300L * 1024 * 1024, false, root),
                new OpenListFileInfo("unknown.chs.srt", 100L, false, root + "/a"),
                new OpenListFileInfo("Show.S02E02.chs.srt", 100L, false, root + "/b")
        ));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeSeriesFiles", task);

        assertThat(organizer.plan.deletions()).isEmpty();
        assertThat(organizer.plan.renames()).hasSize(1);
        assertThat(organizer.plan.cleanupExcludedDirectories()).contains(root + "/a", root + "/b");
    }

    @Test
    void moviePreservesArbitraryNonChineseSubtitlesAndTheirDirectories() {
        OpenListClient client = mock(OpenListClient.class);
        stubPathHelpers(client);
        String savePath = "/movies/Furious 7 (2015)";
        when(client.findFiles(savePath)).thenReturn(List.of(
                new OpenListFileInfo("release.mkv", 300L * 1024 * 1024, false, savePath + "/release"),
                new OpenListFileInfo("arbitrary-language.srt", 100L, false, savePath + "/subs/a"),
                new OpenListFileInfo("arbitrary-language.srt", 100L, false, savePath + "/subs/b"),
                new OpenListFileInfo("English.srt", 100L, false, savePath + "/subs/a"),
                new OpenListFileInfo("dan.srt", 100L, false, savePath + "/subs/a")
        ));
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();

        ReflectionTestUtils.invokeMethod(service(client, organizer), "organizeMovieFiles", movieTask(savePath));

        assertThat(organizer.plan.deletions()).isEmpty();
        assertThat(organizer.plan.renames()).hasSize(1);
        assertThat(organizer.plan.moves()).hasSize(1);
        assertThat(organizer.plan.cleanupExcludedDirectories()).contains(
                savePath + "/subs", savePath + "/subs/a", savePath + "/subs/b");
    }

    @Test
    void movieOrganizeRejectsTinyFilesDisguisedAsVideos() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        String savePath = "/movies/Furious 7 (2015)";
        String releasePath = savePath + "/release";
        String mainVideo = "Fast.&.Furious.7.2015.2160p.WEB-DL.60fps.H265.10bit.AAC-MOMOWEB.mp4";
        String adMkv = "【更多无水印高清电影请访问 www.BBEDDE.com】.MKV";
        String adMp4 = "【更多无水印蓝光原盘请访问 www.BBEDDE.com】.MP4";
        String advertisement = "【更多无水印蓝光电影请访问 www.BBEDDE.com】.DOC";
        when(openListClient.findFiles(savePath)).thenReturn(List.of(
                new OpenListFileInfo(mainVideo, 25_647_428_045L, false, releasePath),
                new OpenListFileInfo(adMkv, 636_976L, false, releasePath),
                new OpenListFileInfo(adMp4, 296_080L, false, releasePath),
                new OpenListFileInfo(advertisement, 296_080L, false, releasePath)
        ));
        when(openListClient.listFiles(savePath)).thenReturn(List.of());
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();
        MagnetIngestService service = service(openListClient, organizer);

        Object result = ReflectionTestUtils.invokeMethod(service, "organizeMovieFiles", movieTask(savePath));

        LibraryOrganizationPlan plan = organizer.plan;
        String targetName = "Furious 7 (2015) 2160p WEB-DL H.265.mp4";
        assertThat(plan.renames()).containsExactly(
                new LibraryOrganizationPlan.RenameOperation(releasePath + "/" + mainVideo, targetName)
        );
        assertThat(plan.moves()).containsExactly(
                new LibraryOrganizationPlan.MoveOperation(releasePath + "/" + targetName, savePath)
        );
        assertThat(plan.deletions())
                .extracting(LibraryOrganizationPlan.DeleteOperation::path)
                .containsExactlyInAnyOrder(
                        releasePath + "/" + adMkv,
                        releasePath + "/" + adMp4,
                        releasePath + "/" + advertisement
        );
        assertThat(plan.expectedTargetNames()).containsExactly(targetName);
        assertThat(ReflectionTestUtils.getField(result, "organizedCount")).isEqualTo(1);
        assertThat(ReflectionTestUtils.getField(result, "skippedCount")).isEqualTo(3);
        assertThat(ReflectionTestUtils.getField(result, "videoCount")).isEqualTo(1);
    }

    @Test
    void movieOrganizeKeepsLargestFilePerLogicalVersionAndRetainsDistinctQualities() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        String savePath = "/movies/Furious 7 (2015)";
        String releasePath = savePath + "/release";
        String smallerContainer = "release.mkv";
        String largerContainer = "release.mp4";
        String distinctQuality = "release.1080p.mkv";
        when(openListClient.findFiles(savePath)).thenReturn(List.of(
                new OpenListFileInfo(smallerContainer, 200L * 1024L * 1024L, false, releasePath),
                new OpenListFileInfo(largerContainer, 300L * 1024L * 1024L, false, releasePath),
                new OpenListFileInfo(distinctQuality, 100L * 1024L * 1024L, false, releasePath)
        ));
        when(openListClient.listFiles(savePath)).thenReturn(List.of());
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();
        MagnetIngestService service = service(openListClient, organizer);

        Object result = ReflectionTestUtils.invokeMethod(service, "organizeMovieFiles", movieTask(savePath));

        assertThat(organizer.plan.renames()).containsExactlyInAnyOrder(
                new LibraryOrganizationPlan.RenameOperation(
                        releasePath + "/" + largerContainer,
                        "Furious 7 (2015).mp4"
                ),
                new LibraryOrganizationPlan.RenameOperation(
                        releasePath + "/" + distinctQuality,
                        "Furious 7 (2015) 1080p.mkv"
                )
        );
        assertThat(organizer.plan.deletions()).containsExactly(
                new LibraryOrganizationPlan.DeleteOperation(releasePath + "/" + smallerContainer)
        );
        assertThat(ReflectionTestUtils.getField(result, "organizedCount")).isEqualTo(2);
        assertThat(ReflectionTestUtils.getField(result, "skippedCount")).isEqualTo(1);
        assertThat(ReflectionTestUtils.getField(result, "videoCount")).isEqualTo(2);
    }

    @Test
    void movieOrganizeStopsWithoutExecutingPlanWhenVideoSizeIsMissing() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        String savePath = "/movies/Furious 7 (2015)";
        when(openListClient.findFiles(savePath)).thenReturn(List.of(
                new OpenListFileInfo("release.mkv", null, false, savePath + "/release")
        ));
        when(openListClient.listFiles(savePath)).thenReturn(List.of());
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();
        MagnetIngestService service = service(openListClient, organizer);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                service,
                "organizeMovieFiles",
                movieTask(savePath)
        ))
                .isInstanceOf(OpenListClientException.class)
                .hasMessageContaining("OpenList 未返回电影视频文件大小")
                .hasMessageContaining("release.mkv");
        assertThat(organizer.plan).isNull();
    }

    @Test
    void animeSeriesOrganizeDeletesAuxiliaryDirectoryWithoutScanningIt() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        when(openListClient.listFiles("/anime/Show/Season 2")).thenReturn(List.of(
                new OpenListFileInfo("[Release]", null, true, "/anime/Show/Season 2")
        ));
        when(openListClient.listFiles("/anime/Show/Season 2/[Release]")).thenReturn(List.of(
                new OpenListFileInfo("[Group] Show - S02E01 1080p.mkv", 1024L, false, "/anime/Show/Season 2/[Release]"),
                new OpenListFileInfo("Scans", null, true, "/anime/Show/Season 2/[Release]")
        ));
        MagnetIngestService service = service(openListClient);

        ReflectionTestUtils.invokeMethod(service, "organizeSeriesFiles", animeSeriesTask());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deletedNames = ArgumentCaptor.forClass(List.class);
        verify(openListClient).remove(eq("/anime/Show/Season 2/[Release]"), deletedNames.capture());
        assertThat(deletedNames.getValue()).containsExactly("Scans");
        verify(openListClient, never()).findFiles(anyString());
        verify(openListClient, never()).listFiles("/anime/Show/Season 2/[Release]/Scans");
    }


    @Test
    void animeSeriesOrganizeSplitsLargeRenameAndMoveBatches() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        List<OpenListFileInfo> releaseFiles = new ArrayList<>();
        for (int episode = 1; episode <= 25; episode++) {
            releaseFiles.add(new OpenListFileInfo(
                    String.format("[Group] Show - S02E%02d 1080p.mkv", episode),
                    1024L,
                    false,
                    "/anime/Show/Season 2/[Release]"
            ));
        }
        when(openListClient.listFiles("/anime/Show/Season 2")).thenReturn(List.of(
                new OpenListFileInfo("[Release]", null, true, "/anime/Show/Season 2")
        ));
        when(openListClient.listFiles("/anime/Show/Season 2/[Release]")).thenReturn(releaseFiles);
        MagnetIngestService service = service(openListClient);

        ReflectionTestUtils.invokeMethod(service, "organizeSeriesFiles", animeSeriesTask());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> renameBatches = ArgumentCaptor.forClass(Map.class);
        verify(openListClient, times(3)).batchRename(eq("/anime/Show/Season 2/[Release]"), renameBatches.capture());
        assertThat(renameBatches.getAllValues()).extracting(Map::size).containsExactly(10, 10, 5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> moveBatches = ArgumentCaptor.forClass(List.class);
        verify(openListClient, times(3)).move(
                eq("/anime/Show/Season 2/[Release]"),
                eq("/anime/Show/Season 2"),
                moveBatches.capture()
        );
        assertThat(moveBatches.getAllValues()).extracting(List::size).containsExactly(10, 10, 5);
    }

    @Test
    void animeSeriesOrganizeUsesChineseLibraryTitleInsteadOfOriginalTitle() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        String seasonPath = "/anime/无职转生：到了异世界就拿出真本事/Season 1";
        when(openListClient.listFiles(seasonPath)).thenReturn(List.of(
                new OpenListFileInfo(
                        "[Group] Mushoku Tensei - S01E01 1080p.mkv",
                        1024L,
                        false,
                        seasonPath
                )
        ));
        MagnetIngestService service = service(openListClient);
        SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
        task.setId("mushoku-tensei-season-1");
        task.setTaskProductType("ANIME");
        task.setTitle("无职转生：到了异世界就拿出真本事");
        task.setOriginalTitle("無職転生 ～異世界行ったら本気だす～");
        task.setSeasonNumber(1);
        task.setSavePath(seasonPath);
        task.setTempPath(seasonPath);

        ReflectionTestUtils.invokeMethod(service, "organizeSeriesFiles", task);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> renames = ArgumentCaptor.forClass(Map.class);
        verify(openListClient).batchRename(eq(seasonPath), renames.capture());
        assertThat(renames.getValue())
                .containsEntry(
                        "[Group] Mushoku Tensei - S01E01 1080p.mkv",
                        "无职转生：到了异世界就拿出真本事 - S01E01 1080p.mkv"
                );
    }

    @Test
    void animeSeriesOrganizeRecognizesSakuratoVersionedBracketEpisodes() {
        OpenListClient openListClient = mock(OpenListClient.class);
        stubPathHelpers(openListClient);
        String seasonPath = "/anime/想吃掉我的非人少女/Season 1";
        String releaseName = "[Sakurato][202509] Watashi o Tabetai, Hitodenashi [01-13 Fin][1080P][简体内嵌]";
        String releasePath = seasonPath + "/" + releaseName;
        when(openListClient.listFiles(seasonPath)).thenReturn(List.of(
                new OpenListFileInfo(releaseName, 0L, true, seasonPath)));
        List<OpenListFileInfo> files = new ArrayList<>();
        for (int episode = 1; episode <= 13; episode++) {
            files.add(new OpenListFileInfo(
                    "[Sakurato] Watashi o Tabetai, Hitodenashi [%02dv2][AVC-8bit 1080P AAC][CHS].mp4".formatted(episode),
                    652_507_402L, false, releasePath));
        }
        files.add(new OpenListFileInfo("[Sakurato] Poster.jpg", 1_051_820L, false, releasePath));
        when(openListClient.listFiles(releasePath)).thenReturn(files);
        SeriesMagnetIngestTask task = animeSeriesTask();
        task.setTitle("想吃掉我的非人少女");
        task.setSeasonNumber(1);
        task.setSavePath(seasonPath);
        task.setTempPath(seasonPath);
        RecordingLibraryOrganizer organizer = new RecordingLibraryOrganizer();
        Object result = ReflectionTestUtils.invokeMethod(service(openListClient, organizer), "organizeSeriesFiles", task);
        assertThat(ReflectionTestUtils.getField(result, "videoCount")).isEqualTo(13);
        assertThat(organizer.plan.expectedTargetNames()).hasSize(13)
                .contains("想吃掉我的非人少女 - S01E01 1080p AVC.mp4", "想吃掉我的非人少女 - S01E13 1080p AVC.mp4");
    }

    private MagnetIngestService service(OpenListClient openListClient) {
        return service(openListClient, new OpenListLibraryOrganizer(openListClient));
    }

    private MagnetIngestService service(OpenListClient openListClient, LibraryOrganizer libraryOrganizer) {
        return new MagnetIngestService(
                mock(MovieMagnetIngestTaskMapper.class),
                mock(MovieMagnetIngestTaskLogMapper.class),
                mock(SeriesMagnetIngestTaskMapper.class),
                mock(SeriesMagnetIngestTaskLogMapper.class),
                openListClient,
                new OpenListProperties(),
                new MovieSeriesFileRenameService(),
                libraryOrganizer,
                null,
                null,
                null,
                null,
                IngestTargetFixtures.legacy(),
                mock(IngestTaskNotifier.class)
        );
    }

    private MovieMagnetIngestTask movieTask(String savePath) {
        MovieMagnetIngestTask task = new MovieMagnetIngestTask();
        task.setId("furious-7");
        task.setTitle("Furious 7");
        task.setOriginalTitle("Furious 7");
        task.setYear(2015);
        task.setSavePath(savePath);
        task.setTempPath(savePath);
        return task;
    }

    private SeriesMagnetIngestTask animeSeriesTask() {
        SeriesMagnetIngestTask task = new SeriesMagnetIngestTask();
        task.setId("series-1");
        task.setTaskProductType("ANIME");
        task.setTitle("Show");
        task.setSeasonNumber(2);
        task.setSavePath("/anime/Show/Season 2");
        task.setTempPath("/anime/Show/Season 2");
        return task;
    }

    private void stubPathHelpers(OpenListClient openListClient) {
        when(openListClient.normalizePath(anyString())).thenAnswer(invocation -> normalizePath(invocation.getArgument(0)));
        when(openListClient.joinPath(anyString(), anyString())).thenAnswer(invocation ->
                normalizePath(invocation.getArgument(0) + "/" + invocation.getArgument(1)));
    }

    private String normalizePath(String path) {
        String normalized = path == null ? "" : path.trim().replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static class RecordingLibraryOrganizer implements LibraryOrganizer {

        private LibraryOrganizationPlan plan;

        @Override
        public void organize(
                LibraryOrganizationPlan plan,
                LibraryOrganizationProgressObserver progressObserver
        ) {
            this.plan = plan;
        }
    }
}
