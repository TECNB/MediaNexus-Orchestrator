package com.medianexus.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "medianexus.quota")
public class UserQuotaProperties {

    private int dailyContentCreateLimit = 3;

    public int getDailyContentCreateLimit() {
        return dailyContentCreateLimit;
    }

    public void setDailyContentCreateLimit(int dailyContentCreateLimit) {
        this.dailyContentCreateLimit = dailyContentCreateLimit;
    }
}
