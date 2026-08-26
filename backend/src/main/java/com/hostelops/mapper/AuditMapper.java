package com.hostelops.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hostelops.domain.AuditEvent;
import com.hostelops.dto.audit.AuditEventResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AuditMapper {

    private static final Logger log = LoggerFactory.getLogger(AuditMapper.class);

    private final ObjectMapper objectMapper;

    public AuditMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * The entity stores {@code payloadDiff} as a JSON string; the response ships it as
     * a real {@link JsonNode}. Re-parsing on the way out looks wasteful and is the
     * cheaper of the two options: the alternative is mapping the column to
     * {@code JsonNode} in the entity, which makes Hibernate dirty-check a mutable tree
     * on every flush of an append-only table.
     *
     * <p>A row whose JSON will not parse yields a null diff and a warning, not a 500.
     * The audit trail is read when something has already gone wrong -- an endpoint that
     * fails on one malformed row out of a thousand would hide the other nine hundred
     * and ninety-nine at exactly the wrong moment. This should be unreachable: the only
     * writer is {@code AuditAspect}, serialising through this same Jackson instance.
     */
    public AuditEventResponse toResponse(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getEntityType(),
                event.getEntityId(),
                event.getAction(),
                event.getActor() == null ? null : event.getActor().getId(),
                event.getActor() == null ? null : event.getActor().getFullName(),
                readDiff(event),
                event.getCreatedAt());
    }

    private JsonNode readDiff(AuditEvent event) {
        String raw = event.getPayloadDiff();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            log.warn("Audit event {} has an unparseable payload_diff", event.getId(), e);
            return null;
        }
    }
}
