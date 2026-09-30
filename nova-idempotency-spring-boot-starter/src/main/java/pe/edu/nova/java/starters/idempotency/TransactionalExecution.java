package pe.edu.nova.java.starters.idempotency;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Corre una operación idempotente dentro de una transacción de Spring, para que su cambio y el registro de su
 * respuesta se confirmen en el mismo commit (ADR-047). Los métodos {@code @Transactional} del servicio se suman
 * a ella.
 *
 * <p>Vive en su propia clase para que el starter no necesite {@code spring-tx} cuando no hay transacciones.
 */
final class TransactionalExecution {

    private final TransactionTemplate template;

    TransactionalExecution(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    /**
     * Corre la operación. Se confirma solo si devuelve {@link Outcome#STORED}; con cualquier otro resultado se
     * deshace.
     *
     * @param body la operación, que recibe cómo saber si el negocio ya marcó la transacción para deshacerla
     * @return lo que decidió la operación, o {@link Outcome#COMMIT_FAILED} si la transacción no se pudo abrir o
     *     confirmar
     */
    Outcome run(Body body) throws IOException, ServletException {
        try {
            return template.execute(status -> {
                Outcome outcome;
                try {
                    outcome = body.run(status::isRollbackOnly);
                } catch (IOException | ServletException failure) {
                    throw new CheckedFailure(failure);
                }
                if (outcome != Outcome.STORED) {
                    // Marcarla aquí la deshace sin error, aunque un @Transactional del negocio ya la haya marcado.
                    status.setRollbackOnly();
                }
                return outcome;
            });
        } catch (CheckedFailure failure) {
            if (failure.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) failure.getCause();
        } catch (TransactionException notCommitted) {
            return Outcome.COMMIT_FAILED;
        }
    }

    /** Lo que corre dentro de la transacción. */
    @FunctionalInterface
    interface Body {

        Outcome run(BooleanSupplier rolledBack) throws IOException, ServletException;
    }

    /** Cómo terminó una operación idempotente. */
    enum Outcome {
        /** La respuesta quedó guardada. */
        STORED,
        /** La respuesta no se guarda, como un 5xx, y la clave se libera. */
        RELEASE,
        /** El negocio deshizo su transacción, pero la respuesta es definitiva: se guarda fuera de ella. */
        COMPLETE_AFTER,
        /** Otro intento tomó la clave mientras esta operación corría. */
        LOST,
        /** El almacén no pudo guardar la respuesta. */
        FAILED,
        /** La transacción no se pudo abrir o confirmar. */
        COMMIT_FAILED
    }

    /** Lleva una excepción verificada a través del callback de la transacción. */
    private static final class CheckedFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CheckedFailure(Exception cause) {
            super(cause);
        }
    }
}
