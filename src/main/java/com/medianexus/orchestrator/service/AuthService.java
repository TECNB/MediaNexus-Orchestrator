package com.medianexus.orchestrator.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.AuthProperties;
import com.medianexus.orchestrator.dto.auth.request.AuthLoginRequest;
import com.medianexus.orchestrator.dto.auth.request.AuthRegisterRequest;
import com.medianexus.orchestrator.dto.auth.response.AuthSessionResponse;
import com.medianexus.orchestrator.dto.auth.response.AuthUserResponse;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9_-]{3,32}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MAX_EMAIL_LENGTH = 128;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 32;
    private static final String USER_ROLE = "USER";
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String LOGIN_FAILED_MESSAGE = "用户名或密码错误";

    private final UserMapper userMapper;
    private final RegistrationCodeSettingsService registrationCodeSettingsService;
    private final PasswordEncoder passwordEncoder;
    private final EmbyAccountService embyAccountService;

    @Autowired
    public AuthService(
            UserMapper userMapper,
            RegistrationCodeSettingsService registrationCodeSettingsService,
            PasswordEncoder passwordEncoder,
            EmbyAccountService embyAccountService
    ) {
        this.userMapper = userMapper;
        this.registrationCodeSettingsService = registrationCodeSettingsService;
        this.passwordEncoder = passwordEncoder;
        this.embyAccountService = embyAccountService;
    }

    public AuthService(
            UserMapper userMapper,
            RegistrationCodeSettingsService registrationCodeSettingsService,
            PasswordEncoder passwordEncoder
    ) {
        this(userMapper, registrationCodeSettingsService, passwordEncoder, null);
    }

    public AuthService(
            UserMapper userMapper,
            AuthProperties authProperties,
            PasswordEncoder passwordEncoder
    ) {
        this(userMapper, new RegistrationCodeSettingsService(null, authProperties), passwordEncoder, null);
    }

    @Transactional
    public AuthSessionResponse register(AuthRegisterRequest request) {
        try {
            if (request == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "请求不能为空");
            }
            String username = normalizeUsername(request.username());
            String email = normalizeEmail(request.email());
            String password = normalizePassword(request.password());
            String confirmPassword = normalizePassword(request.confirmPassword());
            if (!password.equals(confirmPassword)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "两次输入的密码不一致");
            }
            RegistrationCodeSettingsService.RegistrationCodeSetting registrationCodeSetting =
                    registrationCodeSettingsService.lockEffectiveRegistrationCode();
            validateRegistrationCode(request.registrationCode(), registrationCodeSetting);
            ensureUsernameAvailable(username);
            ensureEmailAvailable(email);

            User user = new User();
            user.setUsername(username);
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setRole(USER_ROLE);
            user.setInvitedByUserId(registrationCodeSetting.inviterUserId());
            user.setInvitedByUsername(registrationCodeSetting.inviterUsername());
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException exception) {
                throw duplicateAccountException(username, email);
            }
            String embyUserId = embyAccountService.provisionUser(user);
            registerEmbyRollbackCleanup(embyUserId);
            userMapper.updateEmbyUserId(user.getId(), embyUserId);
            user.setEmbyUserId(embyUserId);
            registrationCodeSettingsService.rotateRegistrationCode();
            AuthSessionResponse response = loginUser(getExistingUser(user.getId()));
            log.info("User registered userId={} username={}", user.getId(), username);
            return response;
        } catch (BusinessException exception) {
            log.warn(
                    "User registration failed username={} emailKey={} status={} reason={}",
                    request == null ? null : logValue(request.username()),
                    request == null ? null : accountKey(request.email()),
                    exception.getHttpStatus().value(),
                    exception.getMessage()
            );
            throw exception;
        }
    }

    public AuthSessionResponse login(AuthLoginRequest request) {
        String accountKey = request == null ? null : accountKey(request.account());
        try {
            if (request == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "请求不能为空");
            }
            String account = normalizeAccount(request.account());
            String password = normalizeLoginPassword(request.password());
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, account)
                    .or()
                    .eq(User::getEmail, account)
                    .last("LIMIT 1"));
            if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE, HttpStatus.UNAUTHORIZED);
            }
            AuthSessionResponse response = loginUser(user);
            log.info("User login succeeded userId={} accountKey={}", user.getId(), accountKey);
            return response;
        } catch (BusinessException exception) {
            log.warn(
                    "User login failed accountKey={} status={} reason={}",
                    accountKey,
                    exception.getHttpStatus().value(),
                    exception.getMessage()
            );
            throw exception;
        }
    }

    public void logout() {
        if (StpUtil.isLogin()) {
            Long userId = StpUtil.getLoginIdAsLong();
            StpUtil.logout();
            log.info("User logout succeeded userId={}", userId);
        }
    }

    public AuthUserResponse me() {
        return toUserResponse(requireCurrentUser());
    }

    private AuthSessionResponse loginUser(User user) {
        StpUtil.login(user.getId());
        return new AuthSessionResponse(StpUtil.getTokenValue(), toUserResponse(user));
    }

    public User requireCurrentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        if (user == null) {
            StpUtil.logout();
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    public User requireAdminUser() {
        User user = requireCurrentUser();
        if (!ADMIN_ROLE.equalsIgnoreCase(user.getRole())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可操作", HttpStatus.FORBIDDEN);
        }
        return user;
    }

    private User getExistingUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "用户创建失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return user;
    }

    private void validateRegistrationCode(
            String registrationCode,
            RegistrationCodeSettingsService.RegistrationCodeSetting setting
    ) {
        String expectedRegistrationCode = setting.registrationCode();
        if (!StringUtils.hasText(expectedRegistrationCode)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "注册暂未开放", HttpStatus.FORBIDDEN);
        }
        if (!registrationCode.trim().equals(expectedRegistrationCode)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "注册码无效", HttpStatus.FORBIDDEN);
        }
    }

    private void registerEmbyRollbackCleanup(String embyUserId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    embyAccountService.deleteProvisionedUser(embyUserId);
                }
            }
        });
    }

    private String normalizeUsername(String username) {
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名需为 3-32 位小写字母、数字、下划线或短横线");
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式无效");
        }
        return normalized;
    }

    private String normalizePassword(String password) {
        if (password == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码不能为空");
        }
        String normalized = password.trim();
        if (normalized.length() < MIN_PASSWORD_LENGTH || normalized.length() > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "密码需为 8-32 位");
        }
        return normalized;
    }

    private String normalizeLoginPassword(String password) {
        if (password == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE, HttpStatus.UNAUTHORIZED);
        }
        String normalized = password.trim();
        if (normalized.length() < MIN_PASSWORD_LENGTH || normalized.length() > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, LOGIN_FAILED_MESSAGE, HttpStatus.UNAUTHORIZED);
        }
        return normalized;
    }

    private String normalizeAccount(String account) {
        return account.trim().toLowerCase(Locale.ROOT);
    }

    private String accountKey(String value) {
        if (!StringUtils.hasText(value)) {
            return "blank";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String logValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "blank";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\r\\n\\t]+", " ");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    private void ensureUsernameAvailable(String username) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (count > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名已被使用");
        }
    }

    private void ensureEmailAvailable(String email) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email));
        if (count > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱已被使用");
        }
    }

    private BusinessException duplicateAccountException(String username, String email) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) > 0) {
            return new BusinessException(ErrorCode.BAD_REQUEST, "用户名已被使用");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getEmail, email)) > 0) {
            return new BusinessException(ErrorCode.BAD_REQUEST, "邮箱已被使用");
        }
        return new BusinessException(ErrorCode.BAD_REQUEST, "账号已存在");
    }

    private AuthUserResponse toUserResponse(User user) {
        return new AuthUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
