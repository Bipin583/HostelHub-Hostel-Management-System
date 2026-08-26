package com.hostelops.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hostelops.config.TransactionConfig;
import com.hostelops.domain.AuditEvent;
import com.hostelops.domain.UserAccount;
import com.hostelops.repository.AuditEventRepository;
import com.hostelops.repository.UserAccountRepository;
import com.hostelops.security.AppUserPrincipal;
import com.hostelops.security.CurrentUserProvider;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * Writes the audit trail. The only writer of {@code audit_events}.
 *
 * <h2>Where in the stack this runs</h2>
 *
 * <p>Inside the caller's transaction, which is the entire reason
 * {@link TransactionConfig} exists -- Boot's default ordering would have put this advice
 * outside it, and the argument against that is written up there. Being inside means the
 * audit row is part of the same commit as the change it describes: neither can exist
 * without the other. Nothing here catches persistence failures, so an audit row that
 * cannot be written rolls back the change. That is the intended trade. An operation whose
 * record cannot be kept is an operation nobody can answer for afterwards, and this
 * application is for a hostel office that has to answer for allocations and money.
 *
 * <h2>What it records</h2>
 *
 * <p>Who, when, which kind of row, which id, and a JSON description of the call. There is
 * no before-image: the aspect sees the method's arguments and its result, not the state
 * the row held on entry. Loading that would mean a query per audited call and, for the
 * pessimistically-locked paths, a second read of a row already locked for update.
 * The previous state of an entity is instead reconstructible by walking its earlier
 * events, which is what {@code idx_audit_entity} is indexed for.
 *
 * <p>Serialising the description is best effort -- a value Jackson cannot handle
 * degrades to its type name -- while persisting the event is not. The distinction is
 * deliberate: a diff is context, but the fact that somebody did something is evidence.
 *
 * <p>{@code payload_diff} is a JSONB column, so a malformed string would be rejected by
 * Postgres at flush time and, per the paragraph above, would roll back the business
 * change. Every path out of {@link #describe} therefore returns valid JSON, including the
 * failure and truncation paths, and the JSON is built through Jackson's node API rather
 * than by string concatenation.
 */
@Aspect
@Component
@Order(TransactionConfig.AUDIT_ADVICE_ORDER)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private static final String REDACTED = "***";

    /**
     * A generous ceiling on the stored description.
     *
     * <p>The trail is meant to be scanned by an operator, and an event whose diff is a
     * hundred kilobytes of bulk-attendance rows is not scannable. Over the limit the
     * arguments are dropped and the result kept, and if that is still too large the
     * description goes entirely -- the event's who, what and which-row never do.
     */
    private static final int MAX_DIFF_CHARS = 8_000;

    /**
     * Names whose values never reach the trail.
     *
     * <p>Matched against both the parameter name and its declared type's simple name, so
     * {@code String signature} and {@code PasswordChangeRequest request} are both caught.
     * This is a name-based rule and therefore only as good as the naming: a secret held
     * in a field of an innocuously-named record would pass through. The convention that
     * keeps that true -- credential-carrying types are named for what they carry -- is
     * cheap to hold and is why no audited method takes a raw credential today.
     */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(password|passwd|secret|token|credential|signature|otp|pin)\\w*");

    private final AuditEventRepository events;
    private final UserAccountRepository users;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    public AuditAspect(
            AuditEventRepository events,
            UserAccountRepository users,
            CurrentUserProvider currentUser,
            ObjectMapper objectMapper) {
        this.events = events;
        this.users = users;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(audited)")
    public Object record(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        Object result = joinPoint.proceed();

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Object[] args = joinPoint.getArgs();

        events.save(AuditEvent.of(
                audited.entity(),
                resolveEntityId(audited, signature, args, result),
                audited.action(),
                actor(),
                describe(signature, args, result)));

        return result;
    }

    /**
     * The account behind the call, or null.
     *
     * <p>Null covers two different situations that the trail records identically, and
     * should: a scheduled job, which has no principal at all, and an account deleted since
     * the event -- {@code audit_events.actor_id} is {@code ON DELETE SET NULL} for exactly
     * that. Refusing to write an event because the actor row had vanished would discard
     * the record over a detail that is already gone.
     */
    private UserAccount actor() {
        return currentUser.optional()
                .map(AppUserPrincipal::getUserId)
                .flatMap(users::findById)
                .orElse(null);
    }

    /**
     * Finds the id of the row this call affected.
     *
     * <p>Preference order is the annotation's {@code idParam} if it names one, otherwise
     * the result's own id. A null outcome is legitimate and stored as such: a bulk
     * operation touches many rows and no single one, and {@code entity_id} is nullable for
     * that reason.
     */
    private Long resolveEntityId(Audited audited, MethodSignature signature, Object[] args, Object result) {
        if (!audited.idParam().isBlank()) {
            return idOf(argumentNamed(audited.idParam(), signature, args));
        }
        return idOf(result);
    }

    private Object argumentNamed(String name, MethodSignature signature, Object[] args) {
        Parameter[] parameters = signature.getMethod().getParameters();
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            if (parameters[i].getName().equals(name)) {
                return args[i];
            }
        }
        // Thrown, not logged and skipped: @Audited(idParam = ...) naming a parameter that
        // does not exist is a coding mistake, and the alternative is a trail of events
        // with no entity id that nobody notices until they need one.
        throw new IllegalStateException(
                "@Audited(idParam = \"%s\") on %s.%s names no parameter of that method. Available: %s"
                        .formatted(
                                name,
                                signature.getDeclaringType().getSimpleName(),
                                signature.getName(),
                                Arrays.stream(parameters).map(Parameter::getName).toList()));
    }

    /**
     * Reads an id out of a value: the number itself, or its {@code id()} / {@code getId()}.
     *
     * <p>Both accessor spellings are tried because responses are records and entities are
     * Lombok-annotated classes. Reflection rather than a shared {@code Identifiable}
     * interface: making every response record implement one would put a marker on
     * thirty-eight DTOs to serve this one caller, and DTOs that happen to have an id are
     * not a family worth naming.
     */
    private static Long idOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        for (String accessor : new String[] {"id", "getId"}) {
            try {
                Method method = value.getClass().getMethod(accessor);
                if (method.invoke(value) instanceof Number number) {
                    return number.longValue();
                }
            } catch (NoSuchMethodException e) {
                // Expected for records (no getId) and for results with no id at all.
            } catch (ReflectiveOperationException | RuntimeException e) {
                log.debug("Could not read {}() from {}", accessor, value.getClass().getName(), e);
                return null;
            }
        }
        return null;
    }

    /**
     * Builds the JSON stored in {@code payload_diff}.
     *
     * <p>Collections and pages are omitted from the result: a method returning a list has
     * changed nothing that the list describes, and a page carries paging metadata that
     * means nothing a week later.
     */
    private String describe(MethodSignature signature, Object[] args, Object result) {
        String method = signature.getDeclaringType().getSimpleName() + "." + signature.getName();
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("method", method);

            ObjectNode arguments = root.putObject("args");
            Parameter[] parameters = signature.getMethod().getParameters();
            for (int i = 0; i < parameters.length && i < args.length; i++) {
                Parameter parameter = parameters[i];
                if (isSensitive(parameter.getName()) || isSensitive(parameter.getType().getSimpleName())) {
                    arguments.put(parameter.getName(), REDACTED);
                } else {
                    arguments.set(parameter.getName(), toNode(args[i]));
                }
            }

            if (result != null && !(result instanceof Page<?>)
                    && !(result instanceof Collection<?>) && !(result instanceof Map<?, ?>)) {
                root.set("result", toNode(result));
            }

            String json = root.toString();
            if (json.length() <= MAX_DIFF_CHARS) {
                return json;
            }

            // Over the limit, shed the arguments first and keep the result. Bulk
            // operations are what reach this branch, and for them the arguments are the
            // thousand-row list while the result is the summary of what the thousand rows
            // did -- so dropping the larger half preserves the more useful one.
            ObjectNode trimmed = objectMapper.createObjectNode();
            trimmed.put("method", method);
            trimmed.put("argsOmitted", json.length() + " characters exceeded the " + MAX_DIFF_CHARS
                    + " character limit");
            if (root.has("result")) {
                trimmed.set("result", root.get("result"));
            }
            String smaller = trimmed.toString();
            if (smaller.length() <= MAX_DIFF_CHARS) {
                return smaller;
            }
            return objectMapper.createObjectNode()
                    .put("method", method)
                    .put("omitted", "payload exceeded " + MAX_DIFF_CHARS + " characters")
                    .toString();
        } catch (RuntimeException e) {
            // Best effort, as the class Javadoc says. The event itself still records who
            // did what to which row, which is the part that has to survive.
            log.warn("Could not describe the arguments of {} for the audit trail", method, e);
            return objectMapper.createObjectNode()
                    .put("method", method)
                    .put("omitted", "payload could not be serialised")
                    .toString();
        }
    }

    /**
     * Serialises one value, degrading to its type name rather than throwing.
     *
     * <p>Reached with ids, request records and response records in practice. An entity
     * would also serialise -- the session is open, so its lazy associations would load --
     * which is a good reason for audited methods to take and return DTOs, and no reason
     * for this method to police it.
     */
    private com.fasterxml.jackson.databind.JsonNode toNode(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.valueToTree(value);
        } catch (IllegalArgumentException e) {
            log.debug("Audit diff could not serialise a {}", value.getClass().getName(), e);
            return objectMapper.getNodeFactory().textNode("<" + value.getClass().getSimpleName() + ">");
        }
    }

    private static boolean isSensitive(String name) {
        return name != null && SENSITIVE.matcher(name).matches();
    }
}
