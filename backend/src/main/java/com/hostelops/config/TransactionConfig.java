package com.hostelops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pins transaction advice at a known position in the interceptor chain.
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>Spring Boot enables transaction management for you, at
 * {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE} -- {@code Integer.MAX_VALUE}.
 * Advice ordering runs low-number-outermost, so at that value the transaction
 * interceptor is the <em>innermost</em> advice on a service method, and every custom
 * aspect necessarily wraps it from outside.
 *
 * <p>That is the wrong side for {@code AuditAspect}. An aspect outside the transaction
 * only regains control after the commit, so its audit row would be written in a second,
 * separate transaction: a change could commit and its audit row then fail, or the audit
 * row could commit describing a change that rolled back. Both leave the trail lying, and
 * a trail that might be lying answers no question worth asking.
 *
 * <p>There is no order value that would put a custom aspect inside advice pinned at
 * {@code MAX_VALUE} -- nothing is larger. So transaction advice is moved to
 * {@link #TRANSACTION_ADVICE_ORDER} instead, leaving room underneath it. Declaring
 * {@code @EnableTransactionManagement} here makes Boot's own auto-configuration back off
 * (it is conditional on no transaction-management configuration being present), so this
 * replaces that setup rather than competing with it.
 *
 * <p>{@code proxyTargetClass = true} restates Boot's default. It is spelled out because
 * taking over the annotation means taking over its defaults too, and silently switching
 * the application from CGLIB subclass proxies to JDK interface proxies would break every
 * bean injected by concrete type.
 *
 * <p>The absolute values do not matter; the gap does. They are constants here rather than
 * literals in two files so that the relationship -- transactions outside, audit inside --
 * is stated once, in code, instead of in a comment somebody has to find.
 */
@Configuration
@EnableTransactionManagement(proxyTargetClass = true, order = TransactionConfig.TRANSACTION_ADVICE_ORDER)
public class TransactionConfig {

    /** Outermost of the two: opens the transaction, and commits or rolls it back. */
    public static final int TRANSACTION_ADVICE_ORDER = 1_000;

    /**
     * Inside the transaction, so an audit row and the change it describes share a fate.
     *
     * <p>Any future aspect that must also run inside a transaction belongs in this range.
     * One that must run outside -- a retry wrapper, say, which has to be able to start a
     * fresh transaction per attempt -- belongs below {@link #TRANSACTION_ADVICE_ORDER}.
     */
    public static final int AUDIT_ADVICE_ORDER = TRANSACTION_ADVICE_ORDER + 100;
}
