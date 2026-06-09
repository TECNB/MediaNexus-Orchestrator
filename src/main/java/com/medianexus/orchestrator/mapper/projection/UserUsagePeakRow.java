package com.medianexus.orchestrator.mapper.projection;

public class UserUsagePeakRow {

    private Integer usedCount;
    private Long userCount;

    public Integer getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(Integer usedCount) {
        this.usedCount = usedCount;
    }

    public Long getUserCount() {
        return userCount;
    }

    public void setUserCount(Long userCount) {
        this.userCount = userCount;
    }
}
