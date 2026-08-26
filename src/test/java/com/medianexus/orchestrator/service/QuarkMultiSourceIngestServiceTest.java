package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.dto.quark.request.QuarkMultiSourceRequest;
import com.medianexus.orchestrator.dto.quark.response.QuarkMultiSourcePreviewResponse;
import com.medianexus.orchestrator.integration.qas.QasClient;
import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskLogMapper;
import com.medianexus.orchestrator.mapper.QuarkIngestTaskMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkMultiSourceIngestServiceTest {

    @Test
    void explainsWhyAZipOnlyShareCannotBePlanned() {
        String shareUrl = "https://pan.quark.cn/s/share123";
        QuarkShareTreeService shareTreeService = mock(QuarkShareTreeService.class);
        when(shareTreeService.inspectShare(shareUrl)).thenReturn(new QasShareTree(
                shareUrl,
                List.of(directory("outer", "动画", directory(
                        "range", "100-200", file("201.zip"), file("202.zip")
                )))
        ));
        QasProperties qasProperties = new QasProperties();
        qasProperties.setVarietyRootPath("/Variety");
        QuarkMultiSourceIngestService service = new QuarkMultiSourceIngestService(
                mock(QasClient.class),
                shareTreeService,
                qasProperties,
                new TmdbProperties(),
                mock(AuthService.class),
                mock(QuarkIngestPlanner.class),
                mock(TmdbClient.class),
                new QuarkShareSourceRegistry(),
                mock(QuarkIngestTaskMapper.class),
                mock(QuarkIngestTaskLogMapper.class)
        );

        QuarkMultiSourcePreviewResponse response = service.previewStructure(
                new QuarkMultiSourceRequest(
                        shareUrl, "测试动画", null, null, null, false, List.of()
                ),
                "VARIETY"
        );

        assertThat(response.ready()).isFalse();
        assertThat(response.sources()).isEmpty();
        assertThat(response.message()).isEqualTo(
                "未发现可播放视频；检测到 2 个 ZIP 压缩包，当前链路不支持解压后入库"
        );
    }

    private static QasShareNode directory(String fid, String name, QasShareNode... children) {
        return new QasShareNode(fid, name, true, null, 0, List.of(children));
    }

    private static QasShareNode file(String name) {
        return new QasShareNode("fid-" + name, name, false, "archive", 1, List.of());
    }
}
