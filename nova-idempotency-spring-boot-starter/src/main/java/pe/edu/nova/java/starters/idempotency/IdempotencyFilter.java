package pe.edu.nova.java.starters.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.springframework.web.filter.OncePerRequestFilter;
import pe.edu.nova.java.libs.idempotency.Attempt;
import pe.edu.nova.java.libs.idempotency.Completion;
import pe.edu.nova.java.libs.idempotency.Decision;
import pe.edu.nova.java.libs.idempotency.Fingerprinter;
import pe.edu.nova.java.libs.idempotency.IdempotencyEngine;
import pe.edu.nova.java.libs.idempotency.IdempotencyStoreException;
import pe.edu.nova.java.libs.idempotency.IdempotentRequest;
import pe.edu.nova.java.libs.idempotency.ScopeResolver;
import pe.edu.nova.java.libs.idempotency.StoredResponse;
import pe.edu.nova.java.starters.idempotency.TransactionalExecution.Outcome;

/**
 * Aplica la idempotencia a las operaciones con {@link Idempotent}: traduce a HTTP cada salida del núcleo.
 *
 * <p>Con la transacción, la operación corre dentro de una que abre este filtro, y la respuesta se guarda antes
 * del commit. Así un cambio del negocio confirmado siempre tiene su respuesta guardada, y una respuesta que no
 * se guarda, como un 5xx, deshace el cambio.
 */
final class IdempotencyFilter extends OncePerRequestFilter {

    /** El header de la clave, como en el borrador del IETF. */
    static final String KEY_HEADER = "Idempotency-Key";

    /** El header que marca una respuesta repetida. */
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final System.Logger LOG = System.getLogger(IdempotencyFilter.class.getName());

    private final IdempotentOperations operations;
    private final IdempotencyEngine engine;
    private final ScopeResolver scopes;
    private final Fingerprinter fingerprinter;
    private final IdempotencyErrorResponder errors;
    private final IdempotencyError scopeMissing;
    private final Duration retryAfter;
    private final TransactionalExecution transactions;

    IdempotencyFilter(
            IdempotentOperations operations,
            IdempotencyEngine engine,
            ScopeResolver scopes,
            Fingerprinter fingerprinter,
            IdempotencyErrorResponder errors,
            IdempotencyError scopeMissing,
            Duration retryAfter,
            TransactionalExecution transactions) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.scopeMissing = Objects.requireNonNull(scopeMissing, "scopeMissing");
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        this.transactions = transactions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!operations.isIdempotent(request)) {
            chain.doFilter(request, response);
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request);
        IdempotentRequest view = new ServletIdempotentRequest(cached);
        Optional<String> scope = scopes.resolve(view);
        if (scope.isEmpty()) {
            errors.respond(response, scopeMissing);
            return;
        }
        Decision decision;
        try {
            decision = engine.begin(
                    request.getHeader(KEY_HEADER), scope.get(), fingerprinter.fingerprint(scope.get(), view));
        } catch (IdempotencyStoreException failure) {
            LOG.log(
                    System.Logger.Level.WARNING,
                    "The idempotency store failed, so the request got a 503: {0}",
                    failure.getMessage());
            errors.respond(response, IdempotencyError.storeUnavailable());
            return;
        }
        switch (decision) {
            case Decision.Execute execute -> execute(cached, response, chain, execute.attempt());
            case Decision.Replay replay -> replay(response, replay.response());
            case Decision.InProgress inProgress ->
                errors.respond(response, IdempotencyError.keyInUse(inProgress.retryAfter()));
            case Decision.KeyReused reused -> errors.respond(response, IdempotencyError.keyReused());
            case Decision.KeyMissing missing -> errors.respond(response, IdempotencyError.keyRequired());
            case Decision.KeyInvalid invalid -> errors.respond(response, IdempotencyError.keyInvalid());
        }
    }

    private void execute(CachedBodyRequest request, HttpServletResponse response, FilterChain chain, Attempt attempt)
            throws IOException, ServletException {
        CapturingResponse capture = new CapturingResponse(response);
        Outcome outcome;
        try {
            outcome = transactions == null
                    ? runAlone(request, capture, chain, attempt)
                    : transactions.run(rolledBack -> runInTransaction(request, capture, chain, attempt, rolledBack));
        } catch (IOException | ServletException | RuntimeException failure) {
            // La operación no llegó a responder: la clave se libera para que el cliente pueda reintentar.
            engine.release(attempt);
            throw failure;
        }
        switch (outcome) {
            case STORED -> capture.copyBodyToResponse();
            case RELEASE -> {
                engine.release(attempt);
                capture.copyBodyToResponse();
            }
            case COMPLETE_AFTER -> {
                engine.complete(attempt, capture.toStored());
                capture.copyBodyToResponse();
            }
            case LOST -> {
                // La operación se deshizo, y el intento que tomó la clave es el que va a responder.
                response.reset();
                errors.respond(response, IdempotencyError.keyInUse(retryAfter));
            }
            case FAILED, COMMIT_FAILED -> {
                engine.release(attempt);
                response.reset();
                errors.respond(response, IdempotencyError.storeUnavailable());
            }
        }
    }

    /** Sin transacción: la operación ya confirmó lo suyo, así que su respuesta sale pase lo que pase. */
    private Outcome runAlone(CachedBodyRequest request, CapturingResponse capture, FilterChain chain, Attempt attempt)
            throws IOException, ServletException {
        chain.doFilter(request, capture);
        if (capture.errorSent()) {
            return Outcome.RELEASE;
        }
        // Un 5xx no se guarda: el núcleo libera la clave.
        engine.complete(attempt, capture.toStored());
        return Outcome.STORED;
    }

    private Outcome runInTransaction(
            CachedBodyRequest request,
            CapturingResponse capture,
            FilterChain chain,
            Attempt attempt,
            BooleanSupplier rolledBack)
            throws IOException, ServletException {
        chain.doFilter(request, capture);
        if (capture.errorSent() || capture.getStatus() >= 500) {
            return Outcome.RELEASE;
        }
        if (rolledBack.getAsBoolean()) {
            return Outcome.COMPLETE_AFTER;
        }
        Completion completion = engine.complete(attempt, capture.toStored());
        return switch (completion) {
            case STORED -> Outcome.STORED;
            case LOST -> Outcome.LOST;
            default -> Outcome.FAILED;
        };
    }

    private static void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        stored.headers().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setHeader(REPLAYED_HEADER, "true");
        byte[] body = stored.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
