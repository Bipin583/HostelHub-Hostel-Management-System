package com.hostelops.mapper;

import com.hostelops.domain.FeePayment;
import com.hostelops.dto.fee.FeePaymentResponse;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    /**
     * Reads {@code fee.getId()} and {@code student.getId()} off the lazy proxies.
     *
     * <p>That is safe without a fetch join, and deliberately so: touching only the
     * identifier of a {@code @ManyToOne} proxy is answered from the proxy itself and
     * issues no query. It is the reason this response carries {@code feeId} rather than
     * a nested invoice -- a payment history is a list, and a nested invoice per row
     * would be N+1 or a fetch join whose only purpose is data the client already has
     * from the fees endpoint.
     *
     * <p>{@code idempotencyKey} is not copied across. See {@link FeePaymentResponse}.
     */
    public FeePaymentResponse toResponse(FeePayment payment) {
        return new FeePaymentResponse(
                payment.getId(),
                payment.getFee().getId(),
                payment.getStudent().getId(),
                payment.getAmountPaise(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getProvider(),
                payment.getProviderOrderId(),
                payment.getProviderPaymentId(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getCompletedAt());
    }
}
