package com.hostelops.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hostelops.config.TransactionConfig;
import com.hostelops.domain.AuditAction;
import com.hostelops.domain.AuditEvent;
import com.hostelops.domain.Role;
import com.hostelops.domain.UserAccount;
import com.hostelops.repository.AuditEventRepository;
import com.hostelops.repository.UserAccountRepository;
import com.hostelops.security.AppUserPrincipal;
import com.hostelops.security.CurrentUserProvider;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Page;

/**
 * {@link AuditAspect} exercised through a real proxy.
 *
 * <p>Every test here goes through {@link AspectJProxyFactory}, which builds the same kind
 * of CGLIB proxy Boot builds and matches the same {@code @annotation(audited)} pointcut.
 * Calling the aspect's {@code record} method directly with a mocked join point would test
 * the body while assuming away the part that actually breaks -- whether the advice is
 * matched and woven at all.
 *
 * <p>This is the proxied path, and it is the only path there is to cover. {@link Audited}
 * says so out loud: a method calling another method of its own class bypasses the proxy and
 * writes no event, and there is nothing to assert about that because there is nothing
 * there. The convention that keeps it from mattering -- audited methods are entry points,
 * called from controllers -- is not something a test can enforce.
 *
 * <p>The claims worth defending, in the order they matter:
 *
 * <ul>
 *   <li>a method that throws writes no event, because {@code proceed()} comes first --
 *       an audit trail that recorded attempts would make every failed authorization look
 *       like a completed action;
 *   <li>sensitive values never reach {@code payload_diff}, matched on the parameter's name
 *       <em>and</em> its type's name;
 *   <li>{@code payload_diff} is always valid JSON, on every path including the truncation
 *       and serialisation-failure ones, because the column is JSONB and Postgres rejecting
 *       a malformed string at flush time would roll back the business change;
 *   <li>a missing actor is recorded rather than refused -- jobs have no principal, and
 *       deleted accounts are why {@code actor_id} is {@code ON DELETE SET NULL};
 *   <li>{@code idParam} naming a parameter that does not exist fails loudly.
 * </ul>
 *
 * <p>What no unit test can show is that the audit row and the change it describes share one
 * commit. That is advice ordering against the transaction interceptor, asserted here only
 * as the {@link Order} value; the behaviour is visible in the lifecycle ITs, where an event
 * written for a rolled-back operation would be a row that should not exist.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditAspect")
class AuditAspectTest {

    private static final long ACTOR_USER_ID = 7L;
    private static final long FEE_ID = 900L;
    private static final long RECEIPT_ID = 4242L;
    private static final String SIGNATURE = "9d4f0c1b6a8e3f52";

    @Mock private AuditEventRepository events;
    @Mock private UserAccountRepository users;
    @Mock private CurrentUserProvider currentUser;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        AuditAspect aspect = new AuditAspect(events, users, currentUser, objectMapper);
        AspectJProxyFactory factory = new AspectJProxyFactory(new Ledger());
        factory.setProxyTargetClass(true);
        factory.addAspect(aspect);
        ledger = factory.getProxy();
    }

    @Nested
    @DisplayName("recording a call")
    class Recording {

        @Test
        @DisplayName("writes one event naming who, what, and which row")
        void writesOneEvent() {
            withActor();

            Receipt receipt = ledger.open(new OpenRequest(FEE_ID, 50_000L), SIGNATURE);

            AuditEvent event = savedEvent();
            assertThat(event.getEntityType()).isEqualTo(AuditEntity.PAYMENT);
            assertThat(event.getAction()).isEqualTo(AuditAction.CREATE);
            assertThat(event.getActor()).isNotNull();
            assertThat(event.getActor().getId()).isEqualTo(ACTOR_USER_ID);
            // No idParam on this method, so the id comes off the result -- read through
            // the record's id() accessor, which is why idOf tries both spellings.
            assertThat(event.getEntityId()).isEqualTo(RECEIPT_ID);

            // The advice returns the target's value untouched. A proxy that swallowed or
            // rebuilt the result would be invisible in production until something compared
            // identities.
            assertThat(receipt.id()).isEqualTo(RECEIPT_ID);
        }

        @Test
        @DisplayName("a method that throws writes no event")
        void aThrowingMethodWritesNoEvent() {
            // proceed() runs before the event is built, so there is no path on which an
            // attempt is recorded as an action. A trail that logged refusals as events
            // would make every failed authorization look like a completed one.
            assertThatThrownBy(() -> ledger.boom())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("the invoice is cancelled");

            verifyNoInteractions(events);
            // The actor is not even looked up: nothing is written, so nobody is named.
            verifyNoInteractions(users);
        }

        @Test
        @DisplayName("an unannotated method is not audited")
        void anUnannotatedMethodIsNotAudited() {
            assertThat(ledger.untouched()).isEqualTo("nothing to see");

            // The pointcut is @annotation(audited), not a package or a naming convention,
            // so reads are silent unless somebody asks for them to be recorded.
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("records the real parameter names, which is what makes redaction work")
        void recordsRealParameterNames() {
            withActor();

            ledger.open(new OpenRequest(FEE_ID, 50_000L), SIGNATURE);

            // Doubles as a guard on the -parameters compiler flag. Without it these would
            // be arg0 and arg1, the SENSITIVE pattern would match nothing, and every
            // signature and password in the application would start landing in the trail
            // with no test failing anywhere.
            JsonNode args = diff().path("args");
            assertThat(args.has("request")).isTrue();
            assertThat(args.has("signature")).isTrue();
            assertThat(args.has("arg0")).isFalse();
        }
    }

    @Nested
    @DisplayName("redacting")
    class Redacting {

        @Test
        @DisplayName("redacts a parameter whose name says it carries a secret")
        void redactsBySensitiveName() {
            withActor();

            ledger.open(new OpenRequest(FEE_ID, 50_000L), SIGNATURE);

            String payload = payloadDiff();
            assertThat(diff().path("args").path("signature").asText()).isEqualTo("***");
            // Asserted against the whole payload, not just that one field: the value must
            // not have reached the trail by any route, including inside the result.
            assertThat(payload).doesNotContain(SIGNATURE);
            // The rest of the call is still described -- redaction is per argument, not a
            // reason to drop the event's context.
            assertThat(diff().path("args").path("request").path("feeId").asLong()).isEqualTo(FEE_ID);
        }

        @Test
        @DisplayName("redacts a parameter whose type says it carries a secret")
        void redactsBySensitiveType() {
            withActor();

            ledger.changeCredentials(FEE_ID, new PasswordChangeRequest("old-one", "new-one"));

            // The parameter is innocuously named `request`; the type is not. Matching both
            // is what stops a credential-carrying record slipping through on the strength
            // of its parameter name.
            assertThat(diff().path("args").path("request").asText()).isEqualTo("***");
            assertThat(payloadDiff()).doesNotContain("old-one").doesNotContain("new-one");
        }
    }

    @Nested
    @DisplayName("resolving the entity id")
    class ResolvingTheId {

        @Test
        @DisplayName("idParam names the argument to read the id from")
        void idParamNamesTheArgument() {
            withActor();

            ledger.cancel(FEE_ID, "duplicate of the September invoice");

            // The result here is a String with no id at all, so without idParam the event
            // would carry a null entity id -- and an audit row that cannot say which
            // invoice was cancelled is not evidence of anything.
            AuditEvent event = savedEvent();
            assertThat(event.getEntityId()).isEqualTo(FEE_ID);
            assertThat(event.getEntityType()).isEqualTo(AuditEntity.FEE);
            assertThat(event.getAction()).isEqualTo(AuditAction.UPDATE);
        }

        @Test
        @DisplayName("idParam naming no parameter of the method is a loud programming error")
        void idParamNamingNothingThrows() {
            // No actor is stubbed, and that is itself an assertion: resolveEntityId is the
            // second argument to AuditEvent.of and actor() the fourth, so the throw happens
            // before anyone is looked up. Under STRICT_STUBS a stubbed actor here would fail
            // the test as unused.
            assertThatThrownBy(() -> ledger.misannotated(FEE_ID))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("idParam")
                    // The available names are in the message, because the fix is to pick
                    // one of them and the developer should not have to go and look.
                    .hasMessageContaining("feeId");

            // Thrown rather than logged and skipped. In production this unwinds the
            // transaction, so the mistake is caught the first time the method is called
            // instead of producing a quiet trail of events with no entity id.
            verify(events, never()).save(any());
        }

        @Test
        @DisplayName("a result with no id is recorded with a null entity id, not refused")
        void aResultWithNoIdIsNull() {
            withActor();

            ledger.listing();

            // entity_id is nullable for exactly this: a call that touches many rows and no
            // single one. The FEE_REMINDER run is the real instance of it.
            AuditEvent event = savedEvent();
            assertThat(event.getEntityId()).isNull();
            assertThat(event.getEntityType()).isEqualTo(AuditEntity.FEE_REMINDER);
        }
    }

    @Nested
    @DisplayName("naming the actor")
    class NamingTheActor {

        @Test
        @DisplayName("a job with no principal is recorded with no actor")
        void noPrincipalMeansNoActor() {
            when(currentUser.optional()).thenReturn(Optional.empty());

            ledger.listing();

            // The nightly jobs run with no security context. Refusing to write the event
            // would mean the only runs with no trail are the unattended ones.
            assertThat(savedEvent().getActor()).isNull();
            verifyNoInteractions(users);
        }

        @Test
        @DisplayName("an actor whose account has since gone is recorded with no actor")
        void aVanishedActorMeansNoActor() {
            when(currentUser.optional()).thenReturn(Optional.of(principal()));
            when(users.findById(ACTOR_USER_ID)).thenReturn(Optional.empty());

            ledger.listing();

            // audit_events.actor_id is ON DELETE SET NULL, so this is the same state a
            // deleted account leaves behind. Discarding the record over a detail that is
            // already gone would lose the part that still matters.
            assertThat(savedEvent().getActor()).isNull();
        }
    }

    @Nested
    @DisplayName("the payload")
    class Payload {

        @Test
        @DisplayName("a collection result is left out of the diff")
        void aCollectionResultIsOmitted() {
            withActor();

            ledger.listing();

            // A method returning a list changed nothing that the list describes, and the
            // list itself is reproducible by running the query again.
            JsonNode diff = diff();
            assertThat(diff.has("result")).isFalse();
            assertThat(diff.path("method").asText()).isEqualTo("Ledger.listing");
        }

        @Test
        @DisplayName("a page result is left out of the diff")
        void aPageResultIsOmitted() {
            withActor();

            ledger.paged();

            // Paging metadata means nothing a week later, which is when somebody reads this.
            assertThat(diff().has("result")).isFalse();
        }

        @Test
        @DisplayName("an oversized payload sheds the arguments and keeps the result")
        void anOversizedPayloadShedsTheArguments() {
            withActor();
            List<String> rows = IntStream.range(0, 500)
                    .mapToObj(i -> "attendance-row-%04d-present-marked".formatted(i))
                    .toList();

            ledger.bulk(rows);

            JsonNode diff = diff();
            // The thousand-row argument is the large half; the summary of what those rows
            // did is the useful half. Dropping the larger one keeps the event scannable.
            assertThat(diff.has("args")).isFalse();
            assertThat(diff.path("argsOmitted").asText()).contains("8000");
            assertThat(diff.path("result").path("id").asLong()).isEqualTo(RECEIPT_ID);
            assertThat(payloadDiff().length()).isLessThanOrEqualTo(8_000);
            // Still names the method, and the event still names the actor and the row.
            assertThat(diff.path("method").asText()).isEqualTo("Ledger.bulk");
            assertThat(savedEvent().getEntityId()).isEqualTo(RECEIPT_ID);
        }

        @Test
        @DisplayName("every payload is valid JSON, because the column is JSONB")
        void everyPayloadIsValidJson() throws Exception {
            withActor();

            ledger.open(new OpenRequest(FEE_ID, 50_000L), SIGNATURE);
            ledger.cancel(FEE_ID, "note");
            ledger.listing();
            ledger.bulk(IntStream.range(0, 500).mapToObj(i -> "row-%04d-padding-padding".formatted(i)).toList());

            ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
            verify(events, times(4)).save(saved.capture());

            // A malformed string would be rejected by Postgres at flush time and -- since
            // this advice runs inside the caller's transaction -- would roll back the change
            // it was describing. Every branch of describe() has to produce parseable JSON,
            // including the truncation and failure ones.
            assertThat(saved.getAllValues()).hasSize(4);
            for (AuditEvent event : saved.getAllValues()) {
                assertThat(event.getPayloadDiff()).isNotBlank();
                assertThat(objectMapper.readTree(event.getPayloadDiff()).isObject())
                        .as("payload_diff must be a JSON object: %s", event.getPayloadDiff())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a value Jackson cannot serialise degrades to its type name, and the event survives")
        void anUnserialisableValueDegrades() throws Exception {
            withActor();

            ledger.awkward(new Unserialisable());

            // Serialising the description is best effort; persisting the event is not. A
            // diff is context, but the fact that somebody did something is evidence.
            AuditEvent event = savedEvent();
            assertThat(event.getEntityType()).isEqualTo(AuditEntity.FEE_REMINDER);
            assertThat(objectMapper.readTree(event.getPayloadDiff()).isObject()).isTrue();
            assertThat(event.getPayloadDiff()).contains("Ledger.awkward");
        }
    }

    @Nested
    @DisplayName("where the advice sits")
    class Ordering {

        @Test
        @DisplayName("orders after the transaction interceptor, so the event shares the commit")
        void ordersInsideTheTransaction() {
            Order order = AuditAspect.class.getAnnotation(Order.class);

            assertThat(order).isNotNull();
            // A higher order value is inner advice. Outside the transaction, an event would
            // commit for a change that rolled back -- a record of something that never
            // happened, which is worse than no record. Boot's default ordering puts this
            // advice outside, which is why TransactionConfig exists at all.
            assertThat(order.value()).isEqualTo(TransactionConfig.AUDIT_ADVICE_ORDER);
            assertThat(order.value()).isGreaterThan(TransactionConfig.TRANSACTION_ADVICE_ORDER);
        }
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private void withActor() {
        when(currentUser.optional()).thenReturn(Optional.of(principal()));
        when(users.findById(ACTOR_USER_ID)).thenReturn(Optional.of(actorAccount()));
    }

    private AuditEvent savedEvent() {
        ArgumentCaptor<AuditEvent> saved = ArgumentCaptor.forClass(AuditEvent.class);
        verify(events).save(saved.capture());
        return saved.getValue();
    }

    private String payloadDiff() {
        return savedEvent().getPayloadDiff();
    }

    private JsonNode diff() {
        try {
            return objectMapper.readTree(payloadDiff());
        } catch (Exception e) {
            throw new AssertionError("payload_diff was not JSON: " + payloadDiff(), e);
        }
    }

    private static AppUserPrincipal principal() {
        return new AppUserPrincipal(
                ACTOR_USER_ID, "it_warden", "{noop}unused", Role.WARDEN, null, null, true);
    }

    private static UserAccount actorAccount() {
        UserAccount account = new UserAccount();
        account.setId(ACTOR_USER_ID);
        account.setUsername("it_warden");
        account.setFullName("R. Iyer");
        account.setRole(Role.WARDEN);
        return account;
    }

    // ---------------------------------------------------------------------------------
    // The target. Public and non-final so CGLIB can subclass it, with one method per
    // branch of the aspect -- deliberately a stand-in rather than a real service, so a
    // change to PaymentService cannot quietly stop exercising a branch here.
    // ---------------------------------------------------------------------------------

    public static class Ledger {

        @Audited(entity = AuditEntity.PAYMENT, action = AuditAction.CREATE)
        public Receipt open(OpenRequest request, String signature) {
            return new Receipt(RECEIPT_ID, "mock");
        }

        @Audited(entity = AuditEntity.FEE, action = AuditAction.UPDATE, idParam = "feeId")
        public String cancel(Long feeId, String note) {
            return "cancelled";
        }

        @Audited(entity = AuditEntity.STUDENT, action = AuditAction.UPDATE, idParam = "feeId")
        public String changeCredentials(Long feeId, PasswordChangeRequest request) {
            return "changed";
        }

        /** {@code idParam} names a parameter this method does not have. */
        @Audited(entity = AuditEntity.FEE, action = AuditAction.UPDATE, idParam = "invoiceId")
        public String misannotated(Long feeId) {
            return "done";
        }

        @Audited(entity = AuditEntity.FEE_REMINDER, action = AuditAction.UPDATE)
        public List<String> listing() {
            return List.of("one", "two");
        }

        @Audited(entity = AuditEntity.FEE_REMINDER, action = AuditAction.UPDATE)
        public Page<String> paged() {
            return Page.empty();
        }

        @Audited(entity = AuditEntity.ATTENDANCE, action = AuditAction.UPDATE)
        public Receipt bulk(List<String> rows) {
            return new Receipt(RECEIPT_ID, "bulk");
        }

        @Audited(entity = AuditEntity.FEE_REMINDER, action = AuditAction.UPDATE)
        public String awkward(Unserialisable value) {
            return "done";
        }

        @Audited(entity = AuditEntity.PAYMENT, action = AuditAction.UPDATE)
        public Receipt boom() {
            throw new IllegalStateException("the invoice is cancelled");
        }

        public String untouched() {
            return "nothing to see";
        }
    }

    public record OpenRequest(Long feeId, Long amountPaise) {
    }

    public record Receipt(Long id, String provider) {
    }

    /** Named for what it carries, which is the convention the name-based redaction rests on. */
    public record PasswordChangeRequest(String current, String replacement) {
    }

    /** No properties for Jackson to find, so serialising it fails rather than returning {}. */
    public static class Unserialisable {
        private final String hidden = "hidden";
    }
}
