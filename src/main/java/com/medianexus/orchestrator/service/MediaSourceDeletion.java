package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaDeletion;
import com.medianexus.orchestrator.integration.quark.QuarkMediaDeletion;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class MediaSourceDeletion {

    private final ObjectProvider<CloudDrive2MediaDeletion> cloudDriveDeletion;
    private final QuarkMediaDeletion quarkDeletion;

    public MediaSourceDeletion(
            ObjectProvider<CloudDrive2MediaDeletion> cloudDriveDeletion,
            QuarkMediaDeletion quarkDeletion
    ) {
        this.cloudDriveDeletion = cloudDriveDeletion;
        this.quarkDeletion = quarkDeletion;
    }

    public void delete(List<String> mediaSourcePaths) {
        delete(mediaSourcePaths, null);
    }

    public void delete(List<String> mediaSourcePaths, String targetDirectoryName) {
        List<String> quarkPaths = mediaSourcePaths.stream().filter(quarkDeletion::supports).toList();
        List<String> cloudDrivePaths = mediaSourcePaths.stream()
                .filter(path -> !quarkDeletion.supports(path))
                .toList();
        if (!quarkPaths.isEmpty()) {
            quarkDeletion.deleteMediaSourcePaths(quarkPaths, targetDirectoryName);
        }
        if (!cloudDrivePaths.isEmpty()) {
            CloudDrive2MediaDeletion deletion = cloudDriveDeletion.getIfAvailable();
            if (deletion == null) {
                throw new IllegalStateException("CD2 文件操作未启用");
            }
            deletion.deleteMediaSourcePaths(cloudDrivePaths);
        }
    }
}
