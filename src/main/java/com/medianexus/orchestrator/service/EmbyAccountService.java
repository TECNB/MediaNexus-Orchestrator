package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.dto.emby.response.EmbyCredentialResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.integration.emby.EmbyUserAccount;
import com.medianexus.orchestrator.model.User;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class EmbyAccountService {

    private static final Logger log = LoggerFactory.getLogger(EmbyAccountService.class);
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String PASSWORD_DERIVATION_CONTEXT = "medianexus:emby-password:v1:";
    private static final int PASSWORD_LENGTH = 8;
    private static final int MIN_SECRET_LENGTH = 16;

    private final EmbyClient embyClient;
    private final EmbyProperties properties;

    public EmbyAccountService(EmbyClient embyClient, EmbyProperties properties) {
        this.embyClient = embyClient;
        this.properties = properties;
    }

    public String provisionUser(User user) {
        requireProvisionableUser(user);
        String templateUsername = requireTemplateUsername();
        String password = derivePassword(user.getId());

        EmbyUserAccount template;
        try {
            template = findUser(templateUsername);
            if (template == null || template.administrator() || template.disabled()) {
                throw serviceUnavailable("Emby 权限模板不可用，请联系管理员", null);
            }
            if (findUser(user.getUsername()) != null) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "Emby 已存在同名用户，请联系管理员",
                        HttpStatus.CONFLICT
                );
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (EmbyClientException exception) {
            throw serviceUnavailable("Emby 账号创建失败，请稍后重试", exception);
        }

        EmbyUserAccount createdUser = null;
        try {
            createdUser = embyClient.createUserFromTemplate(user.getUsername(), template.id());
            embyClient.updateUserPassword(createdUser.id(), password);
            return createdUser.id();
        } catch (EmbyClientException exception) {
            if (createdUser != null) {
                deleteUserQuietly(createdUser.id(), "incomplete provisioning");
            }
            throw serviceUnavailable("Emby 账号创建失败，请稍后重试", exception);
        }
    }

    public EmbyCredentialResponse credentialsFor(User user) {
        if (user == null || user.getId() == null || !StringUtils.hasText(user.getEmbyUserId())) {
            return EmbyCredentialResponse.unmanaged();
        }
        return new EmbyCredentialResponse(
                true,
                user.getUsername(),
                derivePassword(user.getId()),
                user.getEmbyUserId()
        );
    }

    public void deleteProvisionedUser(String embyUserId) {
        deleteUserQuietly(embyUserId, "registration rollback");
    }

    public boolean deleteManagedUser(User user) {
        if (!StringUtils.hasText(user.getEmbyUserId())) {
            return false;
        }
        try {
            embyClient.deleteUser(user.getEmbyUserId());
            return true;
        } catch (EmbyClientException exception) {
            throw serviceUnavailable("Emby 用户删除失败，请稍后重试", exception);
        }
    }

    private EmbyUserAccount findUser(String username) {
        String normalizedUsername = username.trim().toLowerCase(Locale.ROOT);
        return embyClient.listUsers().stream()
                .filter(user -> StringUtils.hasText(user.name()))
                .filter(user -> user.name().trim().toLowerCase(Locale.ROOT).equals(normalizedUsername))
                .findFirst()
                .orElse(null);
    }

    private String derivePassword(Long userId) {
        String secret = requirePasswordSecret();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    (PASSWORD_DERIVATION_CONTEXT + userId).getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
            for (int index = 0; index < PASSWORD_LENGTH; index++) {
                password.append(PASSWORD_ALPHABET.charAt(digest[index] & 31));
            }
            return password.toString();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is not available", exception);
        }
    }

    private String requirePasswordSecret() {
        String secret = properties.getRegistrationPasswordSecret();
        if (!StringUtils.hasText(secret) || secret.trim().length() < MIN_SECRET_LENGTH) {
            throw serviceUnavailable("Emby 注册凭据尚未配置，请联系管理员", null);
        }
        return secret.trim();
    }

    private String requireTemplateUsername() {
        String username = properties.getRegistrationTemplateUsername();
        if (!StringUtils.hasText(username)) {
            throw serviceUnavailable("Emby 权限模板尚未配置，请联系管理员", null);
        }
        return username.trim();
    }

    private void requireProvisionableUser(User user) {
        if (user == null || user.getId() == null || !StringUtils.hasText(user.getUsername())) {
            throw new IllegalArgumentException("MediaNexus user must be persisted before Emby provisioning");
        }
    }

    private void deleteUserQuietly(String embyUserId, String reason) {
        if (!StringUtils.hasText(embyUserId)) {
            return;
        }
        try {
            embyClient.deleteUser(embyUserId);
        } catch (RuntimeException exception) {
            log.error("Emby user cleanup failed embyUserId={} reason={}", embyUserId, reason, exception);
        }
    }

    private BusinessException serviceUnavailable(String message, Throwable cause) {
        BusinessException exception = new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                message,
                HttpStatus.SERVICE_UNAVAILABLE
        );
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }
}
