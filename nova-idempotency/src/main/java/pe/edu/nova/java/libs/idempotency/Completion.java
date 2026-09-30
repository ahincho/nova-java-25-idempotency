package pe.edu.nova.java.libs.idempotency;

/** Cómo terminó un intento: lo que pasó con su registro cuando el conector lo completó o lo liberó. */
public enum Completion {

    /** La respuesta quedó guardada y los reintentos la repetirán. */
    STORED,

    /**
     * La respuesta no se guardó y la clave quedó libre: era un 5xx, que nunca se guarda, o el conector
     * liberó el intento. El cliente puede reintentar y la operación se ejecutará de nuevo.
     */
    RELEASED,

    /**
     * El intento ya no era el dueño de la clave, así que el almacén no cambió nada. Su lock venció y otro
     * intento la tomó, o el registro se purgó. La operación pudo ejecutarse dos veces; el núcleo lo dejó
     * escrito en el log. Un conector que corre la operación dentro de la transacción de negocio debería
     * deshacerla.
     */
    LOST,

    /**
     * El almacén falló al guardar o al liberar. El núcleo lo dejó escrito en el log y no lanzó la
     * excepción: la operación ya ocurrió, y fallarle al cliente ahora lo invita a hacer justo el
     * reintento que esta capacidad quiere evitar. La clave queda tomada hasta que el lock venza.
     */
    FAILED
}
