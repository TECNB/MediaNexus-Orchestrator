package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.CreateTestUserRequest;
import com.medianexus.orchestrator.dto.UpdateTestUserRequest;
import com.medianexus.orchestrator.mapper.TestUserMapper;
import com.medianexus.orchestrator.model.TestUser;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TestUserService {

    private final TestUserMapper testUserMapper;

    public TestUserService(TestUserMapper testUserMapper) {
        this.testUserMapper = testUserMapper;
    }

    public List<TestUser> listUsers() {
        return testUserMapper.selectList(new LambdaQueryWrapper<TestUser>()
                .orderByDesc(TestUser::getId));
    }

    public TestUser getUser(Long id) {
        TestUser user = testUserMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "test user not found");
        }
        return user;
    }

    public TestUser createUser(CreateTestUserRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        validateUsername(request.username());

        TestUser user = new TestUser();
        user.setUsername(request.username().trim());
        user.setEmail(trimToNull(request.email()));
        user.setDisplayName(trimToNull(request.displayName()));
        user.setEnabled(request.enabled() == null || request.enabled());
        testUserMapper.insert(user);
        return getUser(user.getId());
    }

    public TestUser updateUser(Long id, UpdateTestUserRequest request) {
        getUser(id);
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }

        LambdaUpdateWrapper<TestUser> updateWrapper = new LambdaUpdateWrapper<TestUser>()
                .eq(TestUser::getId, id);
        boolean hasUpdates = false;

        if (request.username() != null) {
            validateUsername(request.username());
            updateWrapper.set(TestUser::getUsername, request.username().trim());
            hasUpdates = true;
        }
        if (request.email() != null) {
            updateWrapper.set(TestUser::getEmail, trimToNull(request.email()));
            hasUpdates = true;
        }
        if (request.displayName() != null) {
            updateWrapper.set(TestUser::getDisplayName, trimToNull(request.displayName()));
            hasUpdates = true;
        }
        if (request.enabled() != null) {
            updateWrapper.set(TestUser::getEnabled, request.enabled());
            hasUpdates = true;
        }

        if (hasUpdates) {
            testUserMapper.update(updateWrapper);
        }
        return getUser(id);
    }

    public void deleteUser(Long id) {
        getUser(id);
        testUserMapper.deleteById(id);
    }

    private void validateUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("username is required");
        }
        if (username.trim().length() > 64) {
            throw new IllegalArgumentException("username must be at most 64 characters");
        }
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
