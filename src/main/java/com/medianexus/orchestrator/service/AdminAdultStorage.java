package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.admin.response.AdminAdultStoragePage;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2ClientException;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaStorage;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaStorage.Entry;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminAdultStorage {
    private final AuthService auth;
    private final CloudDrive2MediaStorage storage;
    private final MediaCapacityIndex capacities;

    public AdminAdultStorage(AuthService auth, CloudDrive2MediaStorage storage, MediaCapacityIndex capacities) {
        this.auth = auth;
        this.storage = storage;
        this.capacities = capacities;
    }

    public AdminAdultStoragePage browse(String library, String directory) {
        auth.requireAdminUser();
        List<String> roots = storage.adultRoots(library);
        String selected = directory == null && roots.size() == 1 ? roots.get(0) : directory;
        if (selected == null) return new AdminAdultStoragePage(roots, null, List.of());
        try {
            List<AdminAdultStoragePage.Item> items = storage.browseAdult(library, selected).stream()
                    .map(this::item)
                    .sorted(Comparator.comparing(AdminAdultStoragePage.Item::storageBytes,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(AdminAdultStoragePage.Item::path))
                    .toList();
            return new AdminAdultStoragePage(roots, selected, items);
        } catch (CloudDrive2ClientException exception) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "无法读取网盘目录，请刷新或返回上一级", HttpStatus.BAD_GATEWAY);
        }
    }

    private AdminAdultStoragePage.Item item(Entry entry) {
        var capacity = capacities.entry(entry);
        return new AdminAdultStoragePage.Item(entry.path(), entry.directory(), entry.id(),
                entry.directory() || entry.bytes() < 0 ? null : entry.bytes(), capacity.bytes(), capacity.status());
    }
}
