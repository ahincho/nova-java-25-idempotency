/**
 * El almacén en memoria de la capacidad de idempotencia: para desarrollo y pruebas.
 *
 * <p>Los registros viven en el proceso. No sobreviven a un reinicio ni los comparten las réplicas, así que
 * en producción una operación puede ejecutarse dos veces. Por eso se marca como no persistente y un
 * conector puede negarse a arrancar con él, salvo que la configuración lo elija a propósito.
 */
package pe.edu.nova.java.libs.idempotency.memory;
