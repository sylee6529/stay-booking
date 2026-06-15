package com.example.staybooking.infra.payment;

import com.example.staybooking.application.payment.port.ExternalPaymentGateway;
import com.example.staybooking.application.payment.port.GatewayRequest;
import com.example.staybooking.application.payment.port.GatewayResult;
import com.example.staybooking.config.AppProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

@Primary
@Component
public class ProtectedExternalPaymentGateway implements ExternalPaymentGateway {

    private final SimulatedPaymentGateway delegate;
    private final Bulkhead bulkhead;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ProtectedExternalPaymentGateway(SimulatedPaymentGateway delegate, AppProperties properties) {
        this.delegate = delegate;
        AppProperties.PgProtection pg = properties.getPgProtection();
        this.bulkhead = Bulkhead.of("pgGateway", BulkheadConfig.custom()
                .maxConcurrentCalls(pg.getMaxConcurrentCalls())
                .maxWaitDuration(pg.getMaxWaitDuration())
                .build());
        this.circuitBreaker = CircuitBreaker.of("pgGateway", CircuitBreakerConfig.custom()
                .slidingWindowSize(pg.getCircuitSlidingWindowSize())
                .minimumNumberOfCalls(pg.getCircuitMinimumNumberOfCalls())
                .failureRateThreshold(pg.getCircuitFailureRateThreshold())
                .waitDurationInOpenState(pg.getCircuitOpenDuration())
                .permittedNumberOfCallsInHalfOpenState(pg.getCircuitHalfOpenPermittedCalls())
                .recordResult(result -> result instanceof GatewayResult gatewayResult && !gatewayResult.approved())
                .build());
        this.timeLimiter = TimeLimiter.of("pgGateway", TimeLimiterConfig.custom()
                .timeoutDuration(pg.getTimeout())
                .cancelRunningFuture(true)
                .build());
    }

    @Override
    public GatewayResult approve(GatewayRequest request) {
        try {
            return CircuitBreaker.decorateCallable(circuitBreaker,
                            Bulkhead.decorateCallable(bulkhead,
                                    TimeLimiter.decorateFutureSupplier(timeLimiter,
                                            () -> executor.submit(() -> delegate.approve(request)))))
                    .call();
        } catch (CallNotPermittedException e) {
            return GatewayResult.declined("PG_CIRCUIT_OPEN");
        } catch (BulkheadFullException e) {
            return GatewayResult.declined("PG_BULKHEAD_FULL");
        } catch (TimeoutException e) {
            return GatewayResult.declined("PG_TIMEOUT");
        } catch (Exception e) {
            return GatewayResult.declined("PG_ERROR");
        }
    }

    @Override
    public void cancel(String transactionId) {
        delegate.cancel(transactionId);
    }

    CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }
}
