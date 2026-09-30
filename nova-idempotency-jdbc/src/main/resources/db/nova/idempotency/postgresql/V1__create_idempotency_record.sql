-- Nova, capacidad de idempotencia (ADR-047): los registros del almacén JDBC para PostgreSQL.
--
-- Este script lo aplica el servicio con su propio migrador. Se copia a las migraciones del servicio con el
-- número de versión que le toque, por ejemplo db/migration/V3__create_idempotency_record.sql en un servicio
-- que usa Flyway. Si el servicio quiere otro nombre de tabla, lo cambia aquí y se lo da al almacén con
-- tableName().
--
-- Hay una fila por clave de idempotencia de un cliente. Una fila es una de dos cosas:
--
--   * el lock de una operación que está corriendo: tiene dueño (owner_token) y todavía no tiene respuesta;
--   * el registro de una operación que terminó: no tiene dueño y guarda la respuesta (status, headers, body).
--
-- El vencimiento (expires_at) es del lock mientras corre la operación, y de la respuesta cuando termina.
-- Una fila vencida se trata como una clave libre: el siguiente intento la toma con la misma sentencia atómica
-- que inserta una clave nueva. Borrar las vencidas solo sirve para liberar espacio.

create table idempotency_record (
    scope           varchar(255) collate "C" not null,
    idempotency_key varchar(255) collate "C" not null,
    fingerprint     varchar(255)             not null,
    owner_token     varchar(64),
    status          smallint,
    headers         text[],
    body            bytea,
    created_at      timestamp with time zone not null,
    expires_at      timestamp with time zone not null,

    -- La clave siempre lleva su alcance: la misma clave de dos clientes son dos filas. La colación "C" compara
    -- byte a byte, así que k y K, o k y "k ", son claves distintas.
    constraint idempotency_record_pk primary key (scope, idempotency_key),

    -- Un lock tiene dueño y ninguna respuesta; un registro completado no tiene dueño y tiene toda la respuesta.
    constraint idempotency_record_state check (
        (owner_token is not null and status is null and headers is null and body is null)
        or (owner_token is null and status between 100 and 599 and headers is not null and body is not null)
    )
);

-- La purga de los vencidos recorre este índice, del más viejo al más nuevo.
create index idempotency_record_expires_at on idempotency_record (expires_at);

comment on table idempotency_record is
    'Idempotencia de Nova (ADR-047): el lock de una operación en curso o la respuesta guardada de una que terminó';
comment on column idempotency_record.scope is
    'De quién es la clave, como el cliente autenticado; nunca vacío, así que una clave nunca es global';
comment on column idempotency_record.idempotency_key is
    'La clave que mandó el cliente, tal como llegó: de 1 a 255 caracteres ASCII imprimibles';
comment on column idempotency_record.fingerprint is
    'SHA-256 del alcance, el método, la ruta y el cuerpo JSON canónico; otra huella es otro contenido';
comment on column idempotency_record.owner_token is
    'El token del intento que tiene el lock; nulo cuando la respuesta ya está guardada';
comment on column idempotency_record.status is
    'El status HTTP de la respuesta guardada; nulo mientras la operación corre';
comment on column idempotency_record.headers is
    'Los headers repetibles de la respuesta guardada, como pares nombre y valor en un arreglo plano';
comment on column idempotency_record.body is
    'El cuerpo de la respuesta guardada, byte por byte';
comment on column idempotency_record.created_at is
    'Cuándo tomó la clave el intento dueño de la fila';
comment on column idempotency_record.expires_at is
    'Cuándo deja de contar el lock o la respuesta: el lock-ttl mientras corre, la retención después';
