package com.medianexus.orchestrator.controller;

import com.medianexus.orchestrator.common.response.ApiResponse;
import com.medianexus.orchestrator.dto.CreateTestUserRequest;
import com.medianexus.orchestrator.dto.UpdateTestUserRequest;
import com.medianexus.orchestrator.model.TestUser;
import com.medianexus.orchestrator.service.TestUserService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/test-users")
public class TestUserController {

    private final TestUserService testUserService;

    public TestUserController(TestUserService testUserService) {
        this.testUserService = testUserService;
    }

    @GetMapping
    public ApiResponse<List<TestUser>> listUsers() {
        return ApiResponse.success(testUserService.listUsers());
    }

    @GetMapping("/{id}")
    public ApiResponse<TestUser> getUser(@PathVariable Long id) {
        return ApiResponse.success(testUserService.getUser(id));
    }

    @PostMapping
    public ApiResponse<TestUser> createUser(@RequestBody CreateTestUserRequest request) {
        return ApiResponse.success(testUserService.createUser(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<TestUser> updateUser(
            @PathVariable Long id,
            @RequestBody UpdateTestUserRequest request
    ) {
        return ApiResponse.success(testUserService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteUser(@PathVariable Long id) {
        testUserService.deleteUser(id);
        return ApiResponse.success();
    }
}
