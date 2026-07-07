package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.mapper.UserAdminAuditLogMapper;
import com.medianexus.orchestrator.model.UserAdminAuditLog;
import org.springframework.stereotype.Service;

@Service
public class UserAdminAuditService {

    public static final String ACTION_UPDATE_GLOBAL_QUOTA = "UPDATE_GLOBAL_QUOTA";
    public static final String ACTION_UPDATE_USER_QUOTA = "UPDATE_USER_QUOTA";
    public static final String ACTION_RESTORE_USER_QUOTA_DEFAULT = "RESTORE_USER_QUOTA_DEFAULT";
    public static final String ACTION_RESET_USER_USAGE_TODAY = "RESET_USER_USAGE_TODAY";
    public static final String ACTION_GENERATE_REGISTRATION_CODE = "GENERATE_REGISTRATION_CODE";

    private final UserAdminAuditLogMapper auditLogMapper;

    public UserAdminAuditService(UserAdminAuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void record(Long adminUserId, Long targetUserId, String actionType, String oldValue, String newValue) {
        UserAdminAuditLog auditLog = new UserAdminAuditLog();
        auditLog.setAdminUserId(adminUserId);
        auditLog.setTargetUserId(targetUserId);
        auditLog.setActionType(actionType);
        auditLog.setOldValue(oldValue);
        auditLog.setNewValue(newValue);
        auditLogMapper.insert(auditLog);
    }
}
