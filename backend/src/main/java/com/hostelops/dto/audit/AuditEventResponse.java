package com.hostelops.dto.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.hostelops.domain.AuditAction;
import java.time.Instant;

/**
 * One entry in the audit trail.
 *
 * <p>{@code payloadDiff} is a {@link JsonNode} rather than a {@code String} so it
 * serialises as real JSON instead of a quoted, escaped blob the client has to parse a
 * second time. The column is JSONB; keeping it structured all the way out to the
 * client is the point of having chosen JSONB over text.
 *
 * <p>{@code actorName} is null for anything the system did to itself -- the scheduled
 * fee reminder and the absence scan have no user behind them. The client renders that
 * as "System"; the server does not invent an account to avoid the null.
 */
public record AuditEventResponse(
        Long id,
        String entityType,
        Long entityId,
        AuditAction action,
        Long actorId,
        String actorName,
        JsonNode payloadDiff,
        Instant createdAt) {
}
