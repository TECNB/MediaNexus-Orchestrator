package com.medianexus.orchestrator.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("emby_watch_sessions")
public class EmbyWatchSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String embySessionId;

    private String embyUserId;

    private String embyUserName;

    private String itemId;

    private String itemType;

    private String itemName;

    private String seriesId;

    private String seriesName;

    private Integer seasonNumber;

    private Integer episodeNumber;

    private Long runtimeTicks;

    private LocalDateTime startTime;

    private LocalDateTime stopTime;

    private Long startPositionTicks;

    private Long stopPositionTicks;

    private Integer watchSeconds;

    private LocalDate watchDate;

    private String deviceName;

    private String clientName;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmbySessionId() {
        return embySessionId;
    }

    public void setEmbySessionId(String embySessionId) {
        this.embySessionId = embySessionId;
    }

    public String getEmbyUserId() {
        return embyUserId;
    }

    public void setEmbyUserId(String embyUserId) {
        this.embyUserId = embyUserId;
    }

    public String getEmbyUserName() {
        return embyUserName;
    }

    public void setEmbyUserName(String embyUserName) {
        this.embyUserName = embyUserName;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getItemType() {
        return itemType;
    }

    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public String getSeriesId() {
        return seriesId;
    }

    public void setSeriesId(String seriesId) {
        this.seriesId = seriesId;
    }

    public String getSeriesName() {
        return seriesName;
    }

    public void setSeriesName(String seriesName) {
        this.seriesName = seriesName;
    }

    public Integer getSeasonNumber() {
        return seasonNumber;
    }

    public void setSeasonNumber(Integer seasonNumber) {
        this.seasonNumber = seasonNumber;
    }

    public Integer getEpisodeNumber() {
        return episodeNumber;
    }

    public void setEpisodeNumber(Integer episodeNumber) {
        this.episodeNumber = episodeNumber;
    }

    public Long getRuntimeTicks() {
        return runtimeTicks;
    }

    public void setRuntimeTicks(Long runtimeTicks) {
        this.runtimeTicks = runtimeTicks;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getStopTime() {
        return stopTime;
    }

    public void setStopTime(LocalDateTime stopTime) {
        this.stopTime = stopTime;
    }

    public Long getStartPositionTicks() {
        return startPositionTicks;
    }

    public void setStartPositionTicks(Long startPositionTicks) {
        this.startPositionTicks = startPositionTicks;
    }

    public Long getStopPositionTicks() {
        return stopPositionTicks;
    }

    public void setStopPositionTicks(Long stopPositionTicks) {
        this.stopPositionTicks = stopPositionTicks;
    }

    public Integer getWatchSeconds() {
        return watchSeconds;
    }

    public void setWatchSeconds(Integer watchSeconds) {
        this.watchSeconds = watchSeconds;
    }

    public LocalDate getWatchDate() {
        return watchDate;
    }

    public void setWatchDate(LocalDate watchDate) {
        this.watchDate = watchDate;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
