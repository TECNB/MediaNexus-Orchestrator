package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.dto.quark.request.SeriesQuarkIngestRequest;
import com.medianexus.orchestrator.dto.quark.response.QuarkIngestTaskResponse;
import com.medianexus.orchestrator.dto.quark.response.QuarkIngestPreviewResponse;
import com.medianexus.orchestrator.integration.qas.QasClient;
import com.medianexus.orchestrator.integration.qas.QasClientException;
import com.medianexus.orchestrator.integration.qas.QasCreatedTask;
import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.qas.QasTaskCreateCommand;
import com.medianexus.orchestrator.integration.qas.QasExecutionObserver;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskMapper;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.QuarkIngestTaskLog;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuarkIngestServiceTest {

    @Test
    void previewsShareStructureAndPlanWithoutCreatingQasTask() {
        QasClient qasClient = mock(QasClient.class);
        AuthService authService = mock(AuthService.class);
        QasProperties qasProperties = properties();
        TmdbProperties tmdbProperties = new TmdbProperties();
        tmdbProperties.setDefaultLanguage("zh-CN");
        QuarkIngestService service = new QuarkIngestService(
                qasClient,
                qasProperties,
                tmdbProperties,
                new MovieSeriesFileRenameService(),
                authService,
                new QuarkIngestPlanner(),
                mock(TmdbClient.class),
                mock(QuarkIngestTaskMapper.class),
                mock(QuarkIngestTaskLogMapper.class)
        );
        String shareUrl = "https://pan.quark.cn/s/share123";
        when(qasClient.inspectShare(shareUrl)).thenReturn(new QasShareTree(
                shareUrl,
                List.of(directory("wrapper-fid", "测试剧集", file("01.mkv"), file("02.mkv")))
        ));

        QuarkIngestPreviewResponse preview = service.previewSeries(new SeriesQuarkIngestRequest(
                shareUrl,
                "测试剧集",
                null,
                1,
                12345
        ));

        assertThat(preview.ready()).isTrue();
        assertThat(preview.mediaType()).isEqualTo("SERIES");
        assertThat(preview.savePath()).isEqualTo("/TV/测试剧集/Season 01");
        assertThat(preview.videoCount()).isEqualTo(2);
        assertThat(preview.directoryCount()).isEqualTo(1);
        assertThat(preview.plannedTaskCount()).isEqualTo(1);
        assertThat(preview.entries()).singleElement().satisfies(root -> {
            assertThat(root.name()).isEqualTo("测试剧集");
            assertThat(root.children()).extracting(node -> node.name()).containsExactly("01.mkv", "02.mkv");
        });
        verify(qasClient, never()).createTask(any(QasTaskCreateCommand.class));
    }

    @Test
    void reportsPartialWhenOnlyOneOfTwoVersionTasksIsCreated() {
        QasClient qasClient = mock(QasClient.class);
        AuthService authService = mock(AuthService.class);
        QasProperties qasProperties = properties();
        TmdbProperties tmdbProperties = new TmdbProperties();
        tmdbProperties.setDefaultLanguage("zh-CN");
        TmdbClient tmdbClient = mock(TmdbClient.class);
        QuarkIngestTaskMapper taskMapper = mock(QuarkIngestTaskMapper.class);
        QuarkIngestTaskLogMapper taskLogMapper = mock(QuarkIngestTaskLogMapper.class);
        User user = new User();
        user.setId(42L);
        when(authService.requireCurrentUser()).thenReturn(user);
        QuarkIngestService service = new QuarkIngestService(
                qasClient,
                qasProperties,
                tmdbProperties,
                new MovieSeriesFileRenameService(),
                authService,
                new QuarkIngestPlanner(),
                tmdbClient,
                taskMapper,
                taskLogMapper
        );

        String shareUrl = "https://pan.quark.cn/s/9259970f4a63";
        when(qasClient.inspectShare(shareUrl)).thenReturn(new QasShareTree(
                shareUrl,
                List.of(
                        directory("high-fid", "4K高码率", file("01.mkv")),
                        directory("standard-fid", "4K", file("01.mkv"))
                )
        ));
        ObjectMapper mapper = new ObjectMapper();
        when(qasClient.createTask(any(QasTaskCreateCommand.class))).thenAnswer(invocation -> {
            QasTaskCreateCommand command = invocation.getArgument(0);
            if (command.taskName().contains("[4K]")) {
                throw new QasClientException(QasClientException.Reason.UPSTREAM, "duplicate task");
            }
            return new QasCreatedTask(command.taskName(), command.savePath(), mapper.createObjectNode());
        });

        QuarkIngestTaskResponse response = service.ingestSeries(new SeriesQuarkIngestRequest(
                shareUrl,
                "我的阿勒泰",
                null,
                1,
                250923
        ));

        assertThat(response.status()).isEqualTo("PARTIAL");
        assertThat(response.id()).isNotBlank();
        assertThat(response.createdTaskCount()).isEqualTo(1);
        assertThat(response.plannedTaskCount()).isEqualTo(2);
        assertThat(response.message()).contains("已创建 1/2").contains("duplicate task");
        ArgumentCaptor<List<QasCreatedTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(qasClient).triggerTasksNow(captor.capture(), any(QasExecutionObserver.class));
        assertThat(captor.getValue()).singleElement();
        ArgumentCaptor<QuarkIngestTaskLog> logCaptor = ArgumentCaptor.forClass(QuarkIngestTaskLog.class);
        verify(taskLogMapper, atLeastOnce()).insert(logCaptor.capture());
        assertThat(logCaptor.getAllValues())
                .anySatisfy(entry -> {
                    assertThat(entry.getStage()).isEqualTo("rename_preview");
                    assertThat(entry.getDetail()).contains("01.mkv → 我的阿勒泰 - S01E01 - ");
                })
                .anySatisfy(entry -> {
                    assertThat(entry.getStage()).isEqualTo("creating");
                    assertThat(entry.getLevel()).isEqualTo("ERROR");
                    assertThat(entry.getDetail()).contains("duplicate task");
                });
    }

    private static QasShareNode directory(String fid, String name, QasShareNode... children) {
        return new QasShareNode(fid, name, true, null, 0, List.of(children));
    }

    private static QasShareNode file(String name) {
        return new QasShareNode("fid-" + name, name, false, "video", 1, List.of());
    }

    private static QasProperties properties() {
        QasProperties properties = new QasProperties();
        properties.setTvRootPath("/TV");
        properties.setVarietyRootPath("/Variety");
        properties.setMovieRootPath("/Movie");
        return properties;
    }
}
