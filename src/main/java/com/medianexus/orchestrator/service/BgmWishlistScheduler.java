package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每分钟读取当前配置；错过的分钟不补跑，来源日期认领由持久执行边界保证。 */
@Component
public class BgmWishlistScheduler {
    private static final Logger log = LoggerFactory.getLogger(BgmWishlistScheduler.class);
    private final BgmWishlistSourceStore sources;
    private final BgmWishlistExecutionService execution;

    public BgmWishlistScheduler(BgmWishlistSourceStore sources, BgmWishlistExecutionService execution) {
        this.sources = sources;
        this.execution = execution;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        for (var state : sources.list()) {
            try {
                execution.runScheduledIfDue(state.source().id(), now);
            } catch (BusinessException exception) {
                if (!exception.getCode().equals(ErrorCode.CONFLICT.getCode())) {
                    log.warn("BGM 每日执行未完成 sourceId={}", state.source().id(), exception);
                }
            } catch (RuntimeException exception) {
                log.error("BGM 每日执行异常 sourceId={}", state.source().id(), exception);
            }
        }
    }
}
