package org.openphc.cce.receiver.beckn;

/**
 * A referral from Spice, unwrapped from its Beckn {@code update}.
 *
 * @param bundleJson     the FHIR transaction Bundle for the write path, {@code Patient} first
 * @param serviceRequestId the {@code ServiceRequest} id (the referral id, an order number)
 * @param performanceId  the performance id of the {@code ServiceRequest} entry, echoed in {@code on_update}
 * @param contractId     {@code message.contract.id}
 * @param transactionId  {@code context.transactionId}
 * @param messageId      {@code context.messageId}
 * @param bapId          {@code context.bapId}
 * @param networkId      {@code context.networkId}, empty when absent
 */
public record ReferralMessage(
        String bundleJson,
        String serviceRequestId,
        String performanceId,
        String contractId,
        String transactionId,
        String messageId,
        String bapId,
        String networkId
) {
}
