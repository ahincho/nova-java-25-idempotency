package pe.edu.nova.java.libs.idempotency;

import java.time.Duration;

/**
 * Dónde viven los registros de idempotencia: el puerto que implementa cada almacén (ADR-047).
 *
 * <p>Un registro es, o el <strong>lock</strong> de un intento en curso, que tiene un dueño y vence
 * {@code lockTtl} después de tomarse o de renovarse por última vez, o un <strong>registro
 * completado</strong>, que guarda la respuesta, no tiene dueño y vence {@code retention} después de
 * completarse. Un registro vencido es, para {@link #acquire}, una clave libre; {@link #purgeExpired} lo
 * borra más tarde.
 *
 * <p><strong>Cada operación es atómica.</strong> No se lee y después se escribe en dos pasos: de cualquier
 * número de llamadas simultáneas a {@code acquire} sobre una clave libre (o vencida) gana exactamente una,
 * y {@code complete}, {@code release} y {@code renew} son un compare-and-set sobre el token de dueño. Un
 * almacén que lee y escribe por separado deja que dos peticiones ejecuten la operación, que es
 * justo lo que esta capacidad existe para impedir.
 *
 * <p><strong>El token es lo que protege el registro.</strong> {@code complete}, {@code release} y
 * {@code renew} solo exigen ser el dueño: no miran si el lock ya venció. Si venció y nadie tomó la clave,
 * el intento lento todavía puede guardar su respuesta; si otro intento la tomó, el token ya no coincide y
 * la llamada no hace nada. Así un intento que se retrasó nunca pisa el registro del que lo reemplazó.
 *
 * <p><strong>Las claves se distinguen exactamente.</strong> {@code k} y {@code K}, o {@code k} y
 * {@code k } con un espacio al final, son registros distintos, y el mismo texto en dos alcances también.
 *
 * <p><strong>Toda llamada lleva un timeout</strong> (ADR-047), que el núcleo fija y el adaptador aplica:
 * si se vence, lanza {@link IdempotencyStoreException}. Un almacén que nunca espera, como el de memoria, lo
 * ignora. Ni el mensaje de esa excepción ni el de ninguna otra que salga de un adaptador cita la clave, el
 * alcance ni el cuerpo.
 *
 * <p>Se prueba con la suite de contrato del repositorio, que corre igual contra el almacén en memoria y
 * contra el de JDBC.
 */
public interface IdempotencyStore {

    /**
     * Toma la clave para el intento, si está libre.
     *
     * <p>La clave está libre si no tiene registro o si su lock o su registro completado ya vencieron. En
     * ese caso el intento queda como dueño de un lock nuevo, que vence a los {@code lockTtl}, y la llamada
     * devuelve {@link Acquisition.Acquired}. Si no, no cambia nada y devuelve lo que encontró:
     * {@link Acquisition.InFlight} si el lock está vigente, o {@link Acquisition.Completed} si hay una
     * respuesta guardada, en ambos casos con la huella que se guardó con el registro y no con la de quien
     * preguntó.
     *
     * @param key         la clave dentro de su alcance
     * @param owner       el token del intento, nuevo para cada intento
     * @param fingerprint la huella de la petición, que se guarda con el lock
     * @param lockTtl     cuánto dura el lock sin renovarse
     * @param timeout     cuánto se espera al almacén
     * @return lo que encontró
     * @throws IdempotencyStoreException si el almacén no pudo atender la llamada
     */
    Acquisition acquire(ScopedKey key, OwnerToken owner, String fingerprint, Duration lockTtl, Duration timeout);

    /**
     * Convierte el lock del dueño en un registro completado: guarda la respuesta, deja la clave sin dueño y
     * la hace vencer a los {@code retention}.
     *
     * @param key       la clave dentro de su alcance
     * @param owner     el token del intento que la tomó
     * @param response  la respuesta que se repetirá en los reintentos
     * @param retention cuánto se conserva la respuesta
     * @param timeout   cuánto se espera al almacén
     * @return {@code true} si lo hizo; {@code false}, sin cambiar nada, si {@code owner} no es el dueño de
     *         un lock de esa clave: no hay registro, otro intento la tomó o ya está completada
     * @throws IdempotencyStoreException si el almacén no pudo atender la llamada
     */
    boolean complete(ScopedKey key, OwnerToken owner, StoredResponse response, Duration retention, Duration timeout);

    /**
     * Borra el lock del dueño, para que un reintento pueda ejecutar la operación: el resultado no fue
     * final, como un 5xx, o la operación no llegó a correr. Un registro completado nunca se libera.
     *
     * @param key     la clave dentro de su alcance
     * @param owner   el token del intento que la tomó
     * @param timeout cuánto se espera al almacén
     * @return {@code true} si lo hizo; {@code false}, sin cambiar nada, si {@code owner} no es el dueño de
     *         un lock de esa clave
     * @throws IdempotencyStoreException si el almacén no pudo atender la llamada
     */
    boolean release(ScopedKey key, OwnerToken owner, Duration timeout);

    /**
     * El latido del lock: lo hace vencer a los {@code lockTtl} desde ahora. Mientras la operación corre, el
     * núcleo lo renueva a intervalos, para que un lock solo venza cuando el proceso que lo tiene murió.
     *
     * @param key     la clave dentro de su alcance
     * @param owner   el token del intento que la tomó
     * @param lockTtl cuánto dura el lock desde ahora
     * @param timeout cuánto se espera al almacén
     * @return {@code true} si lo hizo; {@code false}, sin cambiar nada, si {@code owner} no es el dueño de
     *         un lock de esa clave. Un registro completado nunca se renueva: su vencimiento sigue siendo la
     *         retención
     * @throws IdempotencyStoreException si el almacén no pudo atender la llamada
     */
    boolean renew(ScopedKey key, OwnerToken owner, Duration lockTtl, Duration timeout);

    /**
     * Borra, como mucho, {@code limit} registros vencidos, sean locks o respuestas. Un registro vencido ya
     * se ignora y {@link #acquire} lo reutiliza, así que esta llamada solo libera espacio; conviene correrla
     * por lotes desde una tarea programada.
     *
     * @param limit   cuántos registros como máximo
     * @param timeout cuánto se espera al almacén
     * @return cuántos borró; si es menos que {@code limit}, no quedan más vencidos
     * @throws IdempotencyStoreException si el almacén no pudo atender la llamada
     */
    int purgeExpired(int limit, Duration timeout);

    /**
     * Dice si el almacén sobrevive a un reinicio del servicio y lo comparten todas sus réplicas.
     *
     * <p>El de memoria devuelve {@code false}. El conector de un framework puede negarse a arrancar con un
     * almacén que no es persistente, salvo que la configuración lo elija a propósito (ADR-047, pregunta 1):
     * en producción, un reinicio o una segunda réplica pierden los registros y una operación se ejecuta
     * dos veces.
     *
     * @return {@code true} si los registros son durables y compartidos
     */
    boolean persistent();
}
