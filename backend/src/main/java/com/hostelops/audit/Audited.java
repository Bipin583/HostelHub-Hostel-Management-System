package com.hostelops.audit;

import com.hostelops.domain.AuditAction;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method whose effect belongs in the audit trail.
 *
 * <p>{@code AuditAspect} does the writing. The annotation carries only what the aspect
 * cannot work out for itself: which kind of record was touched, and what happened to it.
 * Everything else -- who, when, which row, with what arguments -- it reads off the call.
 *
 * <p>The alternative, an {@code audit.record(...)} call at the end of each mutation, was
 * rejected for the reason stated on {@code AuditEvent}: it is something a new endpoint can
 * forget. Under this design the trail is a property of being annotated, and the annotation
 * sits on the line the reviewer is already reading.
 *
 * <h2>Limits worth knowing before relying on it</h2>
 *
 * <p>Advice applies only to calls that arrive through the Spring proxy. A service method
 * calling another method on {@code this} bypasses the proxy and is not audited -- so an
 * audited operation must be the entry point a controller or job calls, not a private step
 * inside one. {@code AuditAspectTest} covers the proxied path; nothing can cover the
 * self-invocation case, because there is nothing to cover.
 *
 * <p>The row is written after the method returns and before the transaction commits, so a
 * method that throws produces no event. That is intentional: a rejected allocation changed
 * nothing, and recording attempts would fill the trail with 404s. Failed <em>logins</em>
 * are a genuine exception to that rule and are handled separately, in {@code AuthService},
 * where a rate limiter needs the counts anyway.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /**
     * What kind of record this touches. Use a constant from {@link AuditEntity}.
     */
    String entity();

    /**
     * Create, update or delete -- the three values {@code ck_audit_action} permits.
     *
     * <p>Coarse on purpose. "Which of the three" plus the arguments in
     * {@code payload_diff} reconstructs what happened; a fifteen-value enum of business
     * verbs would need a migration every time a service grew a method.
     */
    AuditAction action();

    /**
     * The parameter naming the affected row, when the return value cannot supply it.
     *
     * <p>By default the aspect reads {@code id()} or {@code getId()} off the result, which
     * covers every method returning a response record. Methods returning {@code void} --
     * {@code vacate(studentId)}, say -- have to point at a parameter instead.
     *
     * <p>Matched by parameter name, not position, which is why {@code -parameters} is on
     * in the build. An index would survive a reordering of the parameter list and start
     * recording the wrong id; a name that no longer exists throws on the first call, with
     * the available names in the message. Loud and immediate beats subtly wrong.
     */
    String idParam() default "";
}
