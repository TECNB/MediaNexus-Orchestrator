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
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[a-z0-9_-]{3,32}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int MAX_EMAIL_LENGTH = 128;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 32;
    private static final String USER_ROLE = "USER";
    private static final String LOGIN_FAILED_MESSAGE = "用户名或密码错误";

    private final UserMapper userMapper;
    private final AuthProperties authProperties;
    private final PasswordEncoder passwordEncoder;

    public AuthService(
            UserMapper userMapper,
            AuthProperties authProperties,
            PasswordEncoder passwordEncoder
    ) {
        this.userMapper = userMapper;
        this.authProperties = authProperties;
        this.passwordEncoder = passwordEncoder;
    }

    public AuthSessionResponse register(AuthRegisterRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求不能为空");
        }
        validateRegistrationCode(request.registrationCode());
        String username = normalizeUsername(request.username());
        String email = normalizeEmail(request.email());
        String password = normalizePassword(request.password());
        String confirmPassword = normalizePassword(request.confirmPassword());
        if (!password.equals(confirmPassword)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "两次输入的密码不一致");
        }
        ensureUsernameAvailable(username);
        ensureEmailAvailable(email);

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(USER_ROLE);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw duplicateAccountException(username, email);
        }
        return loginUser(getExistingUser(user.getId()));
    }

    public AuthSessionResponse login(AuthLoginRequest request) {
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
        return loginUser(user);
    }

    public void logout() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
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

    private User getExistingUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "用户创建失败", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return user;
    }

    private void validateRegistrationCode(String registrationCode) {
        if (!StringUtils.hasText(registrationCode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "注册码不能为空");
        }
        String expectedRegistrationCode = cleanConfigValue(authProperties.getRegistrationCode());
        if (!StringUtils.hasText(expectedRegistrationCode)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "注册暂未开放", HttpStatus.FORBIDDEN);
        }
        if (!registrationCode.trim().equals(expectedRegistrationCode)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "注册码无效", HttpStatus.FORBIDDEN);
        }
    }

    private String cleanConfigValue(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\"")))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名需为 3-32 位小写字母、数字、下划线或短横线");
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        if (!StringUtils.hasText(email)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱不能为空");
        }
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
        if (!StringUtils.hasText(account)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号不能为空");
        }
        return account.trim().toLowerCase(Locale.ROOT);
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
