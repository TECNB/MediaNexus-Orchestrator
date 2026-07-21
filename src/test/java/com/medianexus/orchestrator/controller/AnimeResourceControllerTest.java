package com.medianexus.orchestrator.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.AuthProperties;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AnimeResourceControllerTest {

    private final AnimeResourceController controller = new AnimeResourceController(
            null,
            null,
            new ForbiddenAuthService()
    );

    @Test
    void rejectsEverySubscriptionRouteForNonAdminUsers() {
        assertForbidden(() -> controller.search("anime"));
        assertForbidden(() -> controller.groups("https://example.invalid"));
        assertForbidden(() -> controller.groupsForItem("mikan-1", "https://example.invalid"));
        assertForbidden(() -> controller.preview(null));
        assertForbidden(() -> controller.subscribe(null));
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .hasMessage("仅管理员可操作");
    }

    private static final class ForbiddenAuthService extends AuthService {

        private ForbiddenAuthService() {
            super(null, (AuthProperties) null, null);
        }

        @Override
        public User requireAdminUser() {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可操作", HttpStatus.FORBIDDEN);
        }
    }
}
