package com.example.staybooking.application.payment;

import com.example.staybooking.application.error.BusinessException;
import com.example.staybooking.application.error.ErrorCode;
import com.example.staybooking.domain.payment.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentOrchestratorTest {

    private final PaymentProcessor pointProcessor = mock(PaymentProcessor.class);
    private final PaymentProcessor cardProcessor = mock(PaymentProcessor.class);

    private PaymentOrchestrator orchestrator() {
        when(pointProcessor.method()).thenReturn(PaymentMethod.Y_POINT);
        when(cardProcessor.method()).thenReturn(PaymentMethod.CREDIT_CARD);
        return new PaymentOrchestrator(List.of(cardProcessor, pointProcessor));
    }

    private PaymentContext context() {
        return new PaymentContext(1L, 10L, "4111-1111-1111-1234", null);
    }

    private ErrorCode errorCodeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    @Test
    void 카드와_YPay를_함께_쓰면_거절한다() {
        PaymentOrchestrator orchestrator = orchestrator();
        PaymentContext ctx = new PaymentContext(1L, 10L, "4111-1111-1111-1234", "tok");
        PaymentCommand command = new PaymentCommand(
                List.of(PaymentMethod.CREDIT_CARD, PaymentMethod.Y_PAY), 150000, 0, ctx);

        assertThatThrownBy(() -> orchestrator.validate(command))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.INVALID_PAYMENT_COMBINATION));
    }

    @Test
    void 포인트_미사용인데_포인트금액이_0이_아니면_거절한다() {
        PaymentOrchestrator orchestrator = orchestrator();
        PaymentCommand command = new PaymentCommand(
                List.of(PaymentMethod.CREDIT_CARD), 150000, 1000, context());

        assertThatThrownBy(() -> orchestrator.validate(command))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.INVALID_PAYMENT_COMBINATION));
    }

    @Test
    void 포인트단독인데_전액이_아니면_거절한다() {
        PaymentOrchestrator orchestrator = orchestrator();
        PaymentCommand command = new PaymentCommand(
                List.of(PaymentMethod.Y_POINT), 150000, 100000,
                new PaymentContext(1L, 10L, null, null));

        assertThatThrownBy(() -> orchestrator.validate(command))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.INVALID_PAYMENT_COMBINATION));
    }

    @Test
    void 카드포함인데_카드번호가_없으면_INVALID_REQUEST로_거절한다() {
        PaymentOrchestrator orchestrator = orchestrator();
        PaymentCommand command = new PaymentCommand(
                List.of(PaymentMethod.CREDIT_CARD), 150000, 0,
                new PaymentContext(1L, 10L, null, null));

        assertThatThrownBy(() -> orchestrator.validate(command))
                .isInstanceOf(BusinessException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    void 카드_포인트_복합결제는_금액을_분배하고_외부에_나머지를_청구한다() {
        PaymentOrchestrator orchestrator = orchestrator();
        PaymentContext ctx = context();
        when(pointProcessor.approve(eq(ctx), eq(30000L))).thenReturn(ProcessorResult.approved("PT-1"));
        when(cardProcessor.approve(eq(ctx), eq(120000L))).thenReturn(ProcessorResult.approved("PG-1"));

        PaymentExecution result = orchestrator.pay(
                new PaymentCommand(List.of(PaymentMethod.CREDIT_CARD, PaymentMethod.Y_POINT), 150000, 30000, ctx));

        assertThat(result.success()).isTrue();
        assertThat(result.transactionId()).isEqualTo("PG-1");
        assertThat(result.pointAmount()).isEqualTo(30000);
        assertThat(result.externalAmount()).isEqualTo(120000);
        assertThat(result.methodsLabel()).isEqualTo("CREDIT_CARD+Y_POINT");
        verify(cardProcessor).approve(eq(ctx), eq(120000L));
    }

    @Test
    void 포인트가_부족하면_외부수단을_호출하지_않고_INSUFFICIENT_POINT로_실패한다() {
        PaymentOrchestrator orchestrator = orchestrator();
        PaymentContext ctx = new PaymentContext(1L, 10L, null, null);
        when(pointProcessor.approve(eq(ctx), eq(150000L)))
                .thenReturn(ProcessorResult.declined(ErrorCode.INSUFFICIENT_POINT, "잔액 부족"));

        PaymentExecution result = orchestrator.pay(
                new PaymentCommand(List.of(PaymentMethod.Y_POINT), 150000, 150000, ctx));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(ErrorCode.INSUFFICIENT_POINT);
        verify(cardProcessor, never()).approve(eq(ctx), eq(150000L));
    }

    @Test
    void 포인트차감후_카드거절이면_포인트를_환불하고_실패를_반환한다() {
        PaymentOrchestrator orchestrator = orchestrator();
        PaymentContext ctx = context();
        when(pointProcessor.approve(eq(ctx), eq(30000L))).thenReturn(ProcessorResult.approved("PT-1"));
        when(cardProcessor.approve(eq(ctx), eq(120000L)))
                .thenReturn(ProcessorResult.declined(ErrorCode.PAYMENT_DECLINED, "CARD_DECLINED"));

        PaymentExecution result = orchestrator.pay(
                new PaymentCommand(List.of(PaymentMethod.CREDIT_CARD, PaymentMethod.Y_POINT), 150000, 30000, ctx));

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo(ErrorCode.PAYMENT_DECLINED);
        verify(pointProcessor).cancel(eq(ctx), eq("PT-1"), eq(30000L));
    }
}
