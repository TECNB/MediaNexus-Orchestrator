package com.medianexus.orchestrator.mapper.projection;

import java.time.LocalDateTime;

public class AdminUserUsageRow {

    private Long id;
    private String username;
    private String email;
    private String role;
    private Integer dailyContentCreateLimitOverride;
    private Long invitedByUserId;
    private String invitedByUsername;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer usedCount;
    private Integer magnetIngestCreateCount;
    private Integer animeSubscribeCreateCount;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getDailyContentCreateLimitOverride() {
        return dailyContentCreateLimitOverride;
    }

    public void setDailyContentCreateLimitOverride(Integer dailyContentCreateLimitOverride) {
        this.dailyContentCreateLimitOverride = dailyContentCreateLimitOverride;
    }

    public Long getInvitedByUserId() {
        return invitedByUserId;
    }

    public void setInvitedByUserId(Long invitedByUserId) {
        this.invitedByUserId = invitedByUserId;
    }

    public String getInvitedByUsername() {
        return invitedByUsername;
    }

    public void setInvitedByUsername(String invitedByUsername) {
        this.invitedByUsername = invitedByUsername;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(Integer usedCount) {
        this.usedCount = usedCount;
    }

    public Integer getMagnetIngestCreateCount() {
        return magnetIngestCreateCount;
    }

    public void setMagnetIngestCreateCount(Integer magnetIngestCreateCount) {
        this.magnetIngestCreateCount = magnetIngestCreateCount;
    }

    public Integer getAnimeSubscribeCreateCount() {
        return animeSubscribeCreateCount;
    }

    public void setAnimeSubscribeCreateCount(Integer animeSubscribeCreateCount) {
        this.animeSubscribeCreateCount = animeSubscribeCreateCount;
    }
}
