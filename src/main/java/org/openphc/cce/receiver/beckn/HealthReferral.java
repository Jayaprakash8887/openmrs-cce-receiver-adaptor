package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One message of the health-referral flow ({@code select}, {@code init}, {@code confirm},
 * {@code status}) from Spice: a Beckn contract whose {@code contractAttributes} is an
 * {@code hrf:HealthReferral} (docs/beckn-integration.md §4A).
 *
 * @param coordinationId {@code contractAttributes.coordinationId}, or {@code contract.id} when absent:
 *                       the referral id, kept constant from {@code select} to {@code status}. It becomes
 *                       the OpenMRS order's accession number.
 * @param contractId     {@code message.contract.id}; the coordination id when absent
 * @param transactionId  {@code context.transactionId}
 * @param messageId      {@code context.messageId}
 * @param bapId          {@code context.bapId}
 * @param networkId      {@code context.networkId}, empty when absent
 * @param contract       {@code message.contract} as sent (echoed in the callback)
 */
public record HealthReferral(
        String coordinationId,
        String contractId,
        String transactionId,
        String messageId,
        String bapId,
        String networkId,
        JsonNode contract
) {
}
