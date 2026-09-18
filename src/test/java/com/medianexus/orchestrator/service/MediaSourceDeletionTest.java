package com.medianexus.orchestrator.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaDeletion;
import com.medianexus.orchestrator.integration.quark.QuarkMediaDeletion;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class MediaSourceDeletionTest {

    @Test
    void routesSmartStrmQuarkSourcesWithoutCallingCloudDrive() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CloudDrive2MediaDeletion> cloudDrive = mock(ObjectProvider.class);
        QuarkMediaDeletion quark = mock(QuarkMediaDeletion.class);
        MediaSourceDeletion deletion = new MediaSourceDeletion(cloudDrive, quark);
        String source = "http://host/smartstrm_fid/QuarkTV/8a98133a4d99445096bf722f8c628dd1/TV/01.mp4";
        when(quark.supports(source)).thenReturn(true);

        deletion.delete(List.of(source), "Series title");

        verify(quark).deleteMediaSourcePaths(List.of(source), "Series title");
        verify(cloudDrive, never()).getIfAvailable();
    }
}
