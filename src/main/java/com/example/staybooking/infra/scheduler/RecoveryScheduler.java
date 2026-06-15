package com.example.staybooking.infra.scheduler;

import com.example.staybooking.application.RecoveryService;
import com.example.staybooking.config.AppProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RecoveryScheduler {

    private final RecoveryService recoveryService;
    private final AppProperties properties;

    public RecoveryScheduler(RecoveryService recoveryService, AppProperties properties) {
        this.recoveryService = recoveryService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "30000")
    public void recover() {
        if (properties.getRecovery().isEnabled()) {
            recoveryService.recoverOnce();
        }
    }
}
