package com.medianexus.orchestrator.integration.qas;

import java.util.List;

public record QasShareNode(
        String fid,
        String name,
        boolean directory,
        String category,
        long size,
        List<QasShareNode> children,
        String shareFidToken
) {

    public QasShareNode(
            String fid,
            String name,
            boolean directory,
            String category,
            long size,
            List<QasShareNode> children
    ) {
        this(fid, name, directory, category, size, children, null);
    }

    public QasShareNode {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
