package com.example.staybooking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private boolean stockSyncOnStartup = false;

    private Duration admissionTtl = Duration.ofSeconds(5);

    private Recovery recovery = new Recovery();

    private PgProtection pgProtection = new PgProtection();

    private PgSimulator pgSimulator = new PgSimulator();

    private UserRateLimit userRateLimit = new UserRateLimit();

    public boolean isStockSyncOnStartup() {
        return stockSyncOnStartup;
    }

    public void setStockSyncOnStartup(boolean stockSyncOnStartup) {
        this.stockSyncOnStartup = stockSyncOnStartup;
    }

    public Duration getAdmissionTtl() {
        return admissionTtl;
    }

    public void setAdmissionTtl(Duration admissionTtl) {
        this.admissionTtl = admissionTtl;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    public void setRecovery(Recovery recovery) {
        this.recovery = recovery;
    }

    public PgProtection getPgProtection() {
        return pgProtection;
    }

    public void setPgProtection(PgProtection pgProtection) {
        this.pgProtection = pgProtection;
    }

    public PgSimulator getPgSimulator() {
        return pgSimulator;
    }

    public void setPgSimulator(PgSimulator pgSimulator) {
        this.pgSimulator = pgSimulator;
    }

    public UserRateLimit getUserRateLimit() {
        return userRateLimit;
    }

    public void setUserRateLimit(UserRateLimit userRateLimit) {
        this.userRateLimit = userRateLimit;
    }

    public static class Recovery {
        private boolean enabled = true;
        private Duration stuckThreshold = Duration.ofSeconds(60);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getStuckThreshold() {
            return stuckThreshold;
        }

        public void setStuckThreshold(Duration stuckThreshold) {
            this.stuckThreshold = stuckThreshold;
        }
    }

    public static class PgProtection {
        private int maxConcurrentCalls = 20;
        private Duration maxWaitDuration = Duration.ZERO;
        private Duration timeout = Duration.ofSeconds(2);
        private int circuitSlidingWindowSize = 10;
        private int circuitMinimumNumberOfCalls = 5;
        private float circuitFailureRateThreshold = 50.0f;
        private Duration circuitOpenDuration = Duration.ofSeconds(10);
        private int circuitHalfOpenPermittedCalls = 2;

        public int getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public void setMaxConcurrentCalls(int maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
        }

        public Duration getMaxWaitDuration() {
            return maxWaitDuration;
        }

        public void setMaxWaitDuration(Duration maxWaitDuration) {
            this.maxWaitDuration = maxWaitDuration;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getCircuitSlidingWindowSize() {
            return circuitSlidingWindowSize;
        }

        public void setCircuitSlidingWindowSize(int circuitSlidingWindowSize) {
            this.circuitSlidingWindowSize = circuitSlidingWindowSize;
        }

        public int getCircuitMinimumNumberOfCalls() {
            return circuitMinimumNumberOfCalls;
        }

        public void setCircuitMinimumNumberOfCalls(int circuitMinimumNumberOfCalls) {
            this.circuitMinimumNumberOfCalls = circuitMinimumNumberOfCalls;
        }

        public float getCircuitFailureRateThreshold() {
            return circuitFailureRateThreshold;
        }

        public void setCircuitFailureRateThreshold(float circuitFailureRateThreshold) {
            this.circuitFailureRateThreshold = circuitFailureRateThreshold;
        }

        public Duration getCircuitOpenDuration() {
            return circuitOpenDuration;
        }

        public void setCircuitOpenDuration(Duration circuitOpenDuration) {
            this.circuitOpenDuration = circuitOpenDuration;
        }

        public int getCircuitHalfOpenPermittedCalls() {
            return circuitHalfOpenPermittedCalls;
        }

        public void setCircuitHalfOpenPermittedCalls(int circuitHalfOpenPermittedCalls) {
            this.circuitHalfOpenPermittedCalls = circuitHalfOpenPermittedCalls;
        }
    }

    public static class PgSimulator {
        private Duration delay = Duration.ZERO;

        public Duration getDelay() {
            return delay;
        }

        public void setDelay(Duration delay) {
            this.delay = delay;
        }
    }

    public static class UserRateLimit {
        private int maxRequests = 5;
        private Duration window = Duration.ofSeconds(1);

        public int getMaxRequests() {
            return maxRequests;
        }

        public void setMaxRequests(int maxRequests) {
            this.maxRequests = maxRequests;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }
    }
}
