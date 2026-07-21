package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.config.AuthProperties;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.mapper.UserActionUsageMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import org.junit.jupiter.api.Test;

class AdminUserManagementServiceTest {

    @Test
    void deletesTheLocalUserAndItsManagedEmbyUser() {
        User admin = user(1L, "admin", "ADMIN", null);
        User target = user(7L, "alice", "USER", "emby-alice");
        UserMapper userMapper = mock(UserMapper.class);
        UserActionUsageMapper usageMapper = mock(UserActionUsageMapper.class);
        TestEmbyAccountService embyAccountService = new TestEmbyAccountService();
        TestAuditService auditService = new TestAuditService();
        AdminUserManagementService service = service(
                admin,
                userMapper,
                usageMapper,
                auditService,
                embyAccountService
        );
        when(userMapper.selectById(7L)).thenReturn(target);
        when(userMapper.deleteById(7L)).thenReturn(1);

        service.deleteUser(7L);

        verify(usageMapper).delete(any());
        verify(userMapper).deleteById(7L);
        assertThat(embyAccountService.deletedUser).isSameAs(target);
        assertThat(auditService.actionType).isEqualTo(UserAdminAuditService.ACTION_DELETE_USER);
        assertThat(auditService.targetUserId).isEqualTo(7L);
    }

    @Test
    void doesNotDeleteTheEmbyUserWhenTheLocalDeleteFails() {
        User admin = user(1L, "admin", "ADMIN", null);
        User target = user(7L, "alice", "USER", "emby-alice");
        UserMapper userMapper = mock(UserMapper.class);
        TestEmbyAccountService embyAccountService = new TestEmbyAccountService();
        AdminUserManagementService service = service(
                admin,
                userMapper,
                mock(UserActionUsageMapper.class),
                new TestAuditService(),
                embyAccountService
        );
        when(userMapper.selectById(7L)).thenReturn(target);
        when(userMapper.deleteById(7L)).thenReturn(0);

        assertThatThrownBy(() -> service.deleteUser(7L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户删除失败，请稍后重试");
        assertThat(embyAccountService.deletedUser).isNull();
    }

    private AdminUserManagementService service(
            User admin,
            UserMapper userMapper,
            UserActionUsageMapper usageMapper,
            UserAdminAuditService auditService,
            EmbyAccountService embyAccountService
    ) {
        return new AdminUserManagementService(
                new TestAuthService(admin),
                userMapper,
                usageMapper,
                null,
                auditService,
                embyAccountService
        );
    }

    private User user(Long id, String username, String role, String embyUserId) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setEmbyUserId(embyUserId);
        return user;
    }

    private static final class TestAuthService extends AuthService {

        private final User admin;

        private TestAuthService(User admin) {
            super(null, (AuthProperties) null, null);
            this.admin = admin;
        }

        @Override
        public User requireAdminUser() {
            return admin;
        }
    }

    private static final class TestEmbyAccountService extends EmbyAccountService {

        private User deletedUser;

        private TestEmbyAccountService() {
            super(null, new EmbyProperties());
        }

        @Override
        public boolean deleteManagedUser(User user) {
            deletedUser = user;
            return user.getEmbyUserId() != null;
        }
    }

    private static final class TestAuditService extends UserAdminAuditService {

        private Long targetUserId;
        private String actionType;

        private TestAuditService() {
            super(null);
        }

        @Override
        public void record(Long adminUserId, Long targetUserId, String actionType, String oldValue, String newValue) {
            this.targetUserId = targetUserId;
            this.actionType = actionType;
        }
    }
}
