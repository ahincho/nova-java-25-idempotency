package pe.edu.nova.java.starters.idempotency;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import pe.edu.nova.java.libs.idempotency.IdempotencySettings;
import pe.edu.nova.java.libs.idempotency.jdbc.JdbcIdempotencyStore;

/**
 * La configuración de la capacidad de idempotencia, bajo {@code nova.idempotency.*} (ADR-047). Cada valor por
 * defecto es el de {@link IdempotencySettings#defaults()}.
 */
@ConfigurationProperties("nova.idempotency")
public class NovaIdempotencyProperties {

    /** Si el starter protege las operaciones con {@code @Idempotent}. */
    private boolean enabled = true;

    /**
     * El almacén de los registros. Sin valor, el starter usa el de JDBC con el {@code DataSource} del servicio,
     * y no arranca si no hay uno. El de memoria no es persistente y hay que elegirlo a propósito.
     */
    private Store store;

    /** El header que dice de quién es la clave, como el {@code X-Customer-Id} que pasa un BFF. */
    private String scopeHeader;

    /** Cuánto se repite una respuesta guardada. */
    private Duration retention = IdempotencySettings.DEFAULT_RETENTION;

    /** Cuánto dura el lock de una operación en curso si no se renueva. */
    private Duration lockTtl = IdempotencySettings.DEFAULT_LOCK_TTL;

    /** Cada cuánto se renueva el lock mientras la operación corre. Sin valor, un tercio del lock; 0 lo apaga. */
    private Duration lockRenewal;

    /** Lo que pide esperar un 409 en {@code Retry-After}. */
    private Duration retryAfter = IdempotencySettings.DEFAULT_RETRY_AFTER;

    /** Cuánto se espera al almacén en cada llamada. */
    private Duration storeTimeout = IdempotencySettings.DEFAULT_STORE_TIMEOUT;

    /** Cuántos registros vencidos borra cada lote de la purga. */
    private int purgeBatchSize = IdempotencySettings.DEFAULT_PURGE_BATCH_SIZE;

    /** Cada cuánto se purgan los registros vencidos; 0 apaga la purga programada. */
    private Duration purgeInterval = Duration.ofHours(1);

    /** Headers que se repiten además de los seis de ADR-047. Nunca cookies, credenciales ni CORS. */
    private List<String> replayHeaders = new ArrayList<>();

    /** La tabla del almacén JDBC, si no es la que crea el script del módulo. */
    private String tableName = JdbcIdempotencyStore.DEFAULT_TABLE;

    /**
     * Si la operación y el registro de su respuesta se confirman en el mismo commit. Aplica con el almacén JDBC
     * y un gestor de transacciones de Spring.
     */
    private boolean sameTransaction = true;

    /** Los almacenes que el starter sabe armar. */
    public enum Store {
        /** PostgreSQL, con el {@code DataSource} del servicio. */
        JDBC,
        /** En memoria: para desarrollo y pruebas, porque se pierde al reiniciar y no lo comparten las réplicas. */
        MEMORY
    }

    /** Crea la configuración con los valores por defecto. */
    public NovaIdempotencyProperties() {}

    /**
     * Arma la configuración del núcleo.
     *
     * @return la configuración
     * @throws IllegalArgumentException si un valor no es válido, como una renovación más larga que el lock
     */
    public IdempotencySettings toSettings() {
        IdempotencySettings settings = IdempotencySettings.defaults()
                .withRetention(retention)
                .withLockTtl(lockTtl)
                .withRetryAfter(retryAfter)
                .withStoreTimeout(storeTimeout)
                .withPurgeBatchSize(purgeBatchSize)
                .withReplayHeaders(replayHeaders);
        return lockRenewal == null ? settings : settings.withLockRenewal(lockRenewal);
    }

    /**
     * Si el starter protege las operaciones.
     *
     * @return {@code true} por defecto
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enciende o apaga el starter.
     *
     * @param enabled si protege las operaciones
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * El almacén elegido.
     *
     * @return el almacén, o {@code null} si no se eligió
     */
    public Store getStore() {
        return store;
    }

    /**
     * Elige el almacén.
     *
     * @param store el almacén
     */
    public void setStore(Store store) {
        this.store = store;
    }

    /**
     * El header del alcance.
     *
     * @return el nombre del header, o {@code null} si el alcance es la identidad autenticada
     */
    public String getScopeHeader() {
        return scopeHeader;
    }

    /**
     * Fija el header del alcance.
     *
     * @param scopeHeader el nombre del header
     */
    public void setScopeHeader(String scopeHeader) {
        this.scopeHeader = scopeHeader;
    }

    /**
     * La retención.
     *
     * @return la retención
     */
    public Duration getRetention() {
        return retention;
    }

    /**
     * Fija la retención.
     *
     * @param retention la retención
     */
    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    /**
     * La duración del lock.
     *
     * @return la duración
     */
    public Duration getLockTtl() {
        return lockTtl;
    }

    /**
     * Fija la duración del lock.
     *
     * @param lockTtl la duración
     */
    public void setLockTtl(Duration lockTtl) {
        this.lockTtl = lockTtl;
    }

    /**
     * La renovación del lock.
     *
     * @return la renovación, o {@code null} si es la de por defecto
     */
    public Duration getLockRenewal() {
        return lockRenewal;
    }

    /**
     * Fija la renovación del lock.
     *
     * @param lockRenewal la renovación
     */
    public void setLockRenewal(Duration lockRenewal) {
        this.lockRenewal = lockRenewal;
    }

    /**
     * La espera que pide un 409.
     *
     * @return la espera
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /**
     * Fija la espera que pide un 409.
     *
     * @param retryAfter la espera
     */
    public void setRetryAfter(Duration retryAfter) {
        this.retryAfter = retryAfter;
    }

    /**
     * El timeout del almacén.
     *
     * @return el timeout
     */
    public Duration getStoreTimeout() {
        return storeTimeout;
    }

    /**
     * Fija el timeout del almacén.
     *
     * @param storeTimeout el timeout
     */
    public void setStoreTimeout(Duration storeTimeout) {
        this.storeTimeout = storeTimeout;
    }

    /**
     * El tamaño de un lote de la purga.
     *
     * @return el tamaño
     */
    public int getPurgeBatchSize() {
        return purgeBatchSize;
    }

    /**
     * Fija el tamaño de un lote de la purga.
     *
     * @param purgeBatchSize el tamaño
     */
    public void setPurgeBatchSize(int purgeBatchSize) {
        this.purgeBatchSize = purgeBatchSize;
    }

    /**
     * El intervalo de la purga.
     *
     * @return el intervalo
     */
    public Duration getPurgeInterval() {
        return purgeInterval;
    }

    /**
     * Fija el intervalo de la purga.
     *
     * @param purgeInterval el intervalo; 0 la apaga
     */
    public void setPurgeInterval(Duration purgeInterval) {
        this.purgeInterval = purgeInterval;
    }

    /**
     * Los headers que se repiten además de los de por defecto.
     *
     * @return los headers
     */
    public List<String> getReplayHeaders() {
        return replayHeaders;
    }

    /**
     * Fija los headers que se repiten además de los de por defecto.
     *
     * @param replayHeaders los headers
     */
    public void setReplayHeaders(List<String> replayHeaders) {
        this.replayHeaders = replayHeaders;
    }

    /**
     * La tabla del almacén JDBC.
     *
     * @return el nombre de la tabla
     */
    public String getTableName() {
        return tableName;
    }

    /**
     * Fija la tabla del almacén JDBC.
     *
     * @param tableName el nombre de la tabla
     */
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /**
     * Si la respuesta se guarda en la transacción de la operación.
     *
     * @return {@code true} por defecto
     */
    public boolean isSameTransaction() {
        return sameTransaction;
    }

    /**
     * Fija si la respuesta se guarda en la transacción de la operación.
     *
     * @param sameTransaction si se guarda en la misma transacción
     */
    public void setSameTransaction(boolean sameTransaction) {
        this.sameTransaction = sameTransaction;
    }
}
