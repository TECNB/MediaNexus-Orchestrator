package com.medianexus.orchestrator.integration.quark;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class QuarkMediaDeletionTest {

    @Test
    void deletesTheExactQuarkFileIdsEmbeddedInSmartStrmUrls() {
        QuarkDirectClient client = mock(QuarkDirectClient.class);
        QuarkMediaDeletion deletion = new QuarkMediaDeletion(client);

        deletion.deleteMediaSourcePaths(List.of(
                "http://host:8024/smartstrm_fid/QuarkTV/8a98133a4d99445096bf722f8c628dd1/TV/title/01.mp4?sign=a",
                "http://host:8024/smartstrm_fid/QuarkTV/f5e5be29427c40d1a7e384fe2049b0c4/TV/title/02.mp4?sign=b"
        ));

        verify(client).deleteOwnedFiles(List.of(
                "8a98133a4d99445096bf722f8c628dd1",
                "f5e5be29427c40d1a7e384fe2049b0c4"
        ));
    }

    @Test
    void deletesTheMatchingSeriesDirectoryInsteadOfLeavingAnEmptyFolder() {
        QuarkDirectClient client = mock(QuarkDirectClient.class);
        QuarkMediaDeletion deletion = new QuarkMediaDeletion(client);
        when(client.deleteOwnedPath("/TV/我有一个朋友")).thenReturn(true);

        deletion.deleteMediaSourcePaths(List.of(
                "http://host:8024/smartstrm_fid/QuarkTV/b33adf9a49fa40b18494fbe7babf9954/TV/%E6%88%91%E6%9C%89%E4%B8%80%E4%B8%AA%E6%9C%8B%E5%8F%8B/Season%2001/01.mp4",
                "http://host:8024/smartstrm_fid/QuarkTV/fc0987b3e2f740c1840c734aeb296f10/TV/%E6%88%91%E6%9C%89%E4%B8%80%E4%B8%AA%E6%9C%8B%E5%8F%8B/Season%2001/02.mp4"
        ), "我有一个朋友");

        verify(client).deleteOwnedPath("/TV/我有一个朋友");
        verify(client, never()).deleteOwnedFiles(org.mockito.ArgumentMatchers.anyList());
    }
}
