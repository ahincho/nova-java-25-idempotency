# nova-java-25-idempotency

La capacidad de idempotencia de Nova Platform. Un cliente puede reintentar una operación sin que se
ejecute dos veces: la petición lleva un `Idempotency-Key`, y el servicio responde al reintento con la
respuesta que ya dio, en vez de repetir la operación. Un reintento nunca compra dos veces, y nunca entrega
los datos de otro cliente.

Las decisiones están en [ADR-047](https://github.com/ahincho/nova-shared-01-docs/blob/main/adrs/shared/ADR-047-idempotencia-detras-de-un-contrato.md),
y la forma del repositorio —un contrato y todas sus implementaciones juntos, con una sola
versión— en [ADR-041](https://github.com/ahincho/nova-shared-01-docs/blob/main/adrs/java/ADR-041-un-repositorio-por-capacidad.md).

## Módulos

| Módulo | `groupId` | Qué es | Estado |
|---|---|---|---|
| `nova-idempotency` | `pe.edu.nova.java.libs` | el contrato, el núcleo y el almacén en memoria; Java puro, sin Spring ni ningún otro framework | fase 1, va en la 0.1.0 |
| `nova-idempotency-jdbc` | `pe.edu.nova.java.libs` | el almacén para PostgreSQL, con JDBC puro | fase 1, va en la 0.1.0 |
| `nova-idempotency-spring-boot-starter` | `pe.edu.nova.java.starters` | conecta la capacidad con Spring Boot y traduce las salidas del núcleo a HTTP | planificado, en la fase 2 |

Todos se publican en `https://maven.pkg.github.com/ahincho/nova-java-25-idempotency` con la misma
versión.

## La primera versión es la 0.1.0

La API todavía no tiene consumidor. La valida el servicio de pedidos de Plaza en la fase 2, y la 1.0.0
llega entonces. Hasta ahí la capacidad se queda en 0.x, y un cambio de la API puede romper.

Release-please sale de 1.0.0 por defecto, así que `.release-please-config.json` fija `initial-version`
en `0.1.0` y el manifest parte de `0.0.0`: el primer release sale como 0.1.0. Mientras la versión sea 0.x,
un cambio incompatible sube el minor y cualquier otro cambio, el patch. Para salir de 0.x, un commit con el
pie `Release-As: 1.0.0`.

## Cómo funciona

El núcleo no habla HTTP: **devuelve resultados, y el conector de cada framework los traduce**. Con la
clave, el alcance y la huella de una petición, `IdempotencyEngine.begin` decide una de estas salidas:

| Salida del núcleo | Qué le corresponde en HTTP |
|---|---|
| `Decision.Execute` | se ejecuta la operación, con un token de dueño nuevo para este intento |
| `Decision.Replay` | la respuesta guardada, con `Idempotent-Replayed: true` |
| `Decision.InProgress` | 409, `IDEMPOTENCY_KEY_IN_USE`, con `Retry-After: 1` |
| `Decision.KeyReused` | 422, `IDEMPOTENCY_KEY_REUSED`: la misma clave llegó con otro contenido |
| `Decision.KeyMissing` | 400, `IDEMPOTENCY_KEY_REQUIRED` |
| `Decision.KeyInvalid` | 400, `IDEMPOTENCY_KEY_INVALID` |

La columna de HTTP es lo que traducirá el starter de la fase 2, con los errores de ADR-031. Hasta
entonces, un servicio usa el núcleo directamente:

```java
IdempotencyStore store = JdbcIdempotencyStore.builder(dataSource).build();
IdempotencyEngine engine = new IdempotencyEngine(store);   // con los valores por defecto de ADR-047

String scope = ScopeResolver.ofHeader("X-Customer-Id").resolve(request).orElseThrow();
String fingerprint = new Sha256Fingerprinter().fingerprint(scope, request);

switch (engine.begin(idempotencyKey, scope, fingerprint)) {
    case Decision.Execute execute -> {
        try {
            StoredResponse response = placeTheOrder();
            engine.complete(execute.attempt(), response);   // un 5xx no se guarda: libera la clave
            reply(response);
        } catch (RuntimeException failure) {
            engine.release(execute.attempt());              // para que el cliente pueda reintentar
            throw failure;
        }
    }
    case Decision.Replay replay -> reply(replay.response());                       // más Idempotent-Replayed
    case Decision.InProgress inProgress -> conflict(inProgress.retryAfterSeconds());
    case Decision.KeyReused reused -> unprocessable();
    case Decision.KeyMissing missing -> badRequest();
    case Decision.KeyInvalid invalid -> badRequest();
}
```

Todo intento que gana la clave se cierra con `complete` o con `release`. Mientras la operación corre, el
núcleo renueva el lock solo, en un hilo virtual por operación, hasta que el intento se cierra. Al terminar
el servicio se cierra el motor con `close()`.

## Los valores por defecto

Todo se configura con `IdempotencySettings`. El núcleo no lee ninguna fuente de configuración: el starter
leerá `nova.idempotency.*` y armará esa clase.

| Propiedad | Por defecto | Qué es |
|---|---|---|
| `nova.idempotency.retention` | 24 horas | cuánto se repite una respuesta guardada |
| `nova.idempotency.lock-ttl` | 60 s | cuánto dura el lock de una operación en curso, si no se renueva |
| `nova.idempotency.lock-renewal` | 20 s, un tercio del lock | cada cuánto se renueva el lock mientras la operación corre; `0` lo apaga |
| `nova.idempotency.retry-after` | 1 s | lo que pide esperar el 409 |
| `nova.idempotency.store-timeout` | 5 s | cuánto se espera al almacén en cada llamada |
| `nova.idempotency.purge-batch-size` | 1000 | cuántos vencidos borra cada lote de la purga |
| `nova.idempotency.replay-headers` | ninguno más | headers que se repiten además de los seis de abajo |

```java
IdempotencySettings settings = IdempotencySettings.defaults()
        .withRetention(Duration.ofHours(48))
        .withLockTtl(Duration.ofSeconds(30));   // la renovación pasa a ser de 10 s
```

## Las reglas

Las aplica el núcleo siempre, sea cual sea el almacén:

1. **El alcance siempre forma parte de la clave.** La misma clave de dos clientes son dos claves
   distintas, y un cliente nunca recibe la respuesta de otro. No existe una clave global: el núcleo rechaza
   un alcance vacío.
2. **Un 5xx nunca se guarda.** Libera la clave, y el cliente puede reintentar.
3. **El replay solo repite** el status, el cuerpo y los headers `Location`, `Content-Type`,
   `Content-Language`, `Content-Location`, `ETag` y `Last-Modified`. Nunca cookies, credenciales ni headers
   de CORS, ni por configuración.
4. **Ni la clave ni el cuerpo aparecen en un log.** Ni siquiera en las excepciones, y un `toString()` no los
   muestra.
5. **Toda llamada al almacén lleva timeout.**
6. **Cada intento lleva su propio token de dueño.** Una renovación o un cierre de otro intento se rechazan,
   así que un intento lento nunca pisa el registro del que lo reemplazó.
7. **La clave se toma tal cual llega:** de 1 a 255 caracteres ASCII imprimibles. `"abc"` y `abc` son claves
   distintas, y no se recorta nada.

Un intento cuyo lock venció, pero al que nadie le tomó la clave, todavía puede guardar su respuesta: el
token es lo que protege el registro, no el reloj. Así el reintento recibe la respuesta en vez de ejecutar la
operación otra vez.

## Los puertos

Lo que una organización puede reemplazar sin forkear:

| Puerto | Qué decide | Implementación de Nova |
|---|---|---|
| `IdempotencyStore` | adquirir, completar, liberar y renovar un registro, y purgar los vencidos; cada operación es atómica y lleva el token de dueño | `InMemoryIdempotencyStore` y `JdbcIdempotencyStore` |
| `ScopeResolver` | de quién es la clave | `ScopeResolver.ofPrincipal()`, la identidad autenticada, y `ScopeResolver.ofHeader("X-Customer-Id")`, un header configurable |
| `Fingerprinter` | la huella de la petición | `Sha256Fingerprinter` |

`ScopeResolver` y `Fingerprinter` reciben una `IdempotentRequest`, la vista de la petición que arma el
conector de cada framework. Así una organización escribe su resolvedor una sola vez y le sirve en Spring
Boot y en Quarkus.

## La huella

`Sha256Fingerprinter` calcula el SHA-256 del alcance, el método, la ruta y el cuerpo, cada parte precedida
de su largo. Un cuerpo JSON entra en la forma canónica de
[RFC 8785](https://www.rfc-editor.org/rfc/rfc8785): claves ordenadas, sin espacios, con las cadenas y los
números escritos de una sola manera. Un cliente que reserializa el mismo JSON no recibe un 422 falso.

- Un cuerpo que no es JSON entra tal cual, byte por byte, y uno vacío entra como vacío.
- La ruta es la concreta, no la plantilla: `/orders/42`, no `/orders/{id}`. Si la operación depende de la
  query, el conector la incluye.
- El JSON se lee con el parser de streaming de Jackson 2 (`jackson-core`), la única dependencia del núcleo,
  que convive con el Jackson 3 de Spring Boot 4 y con el Jackson 2 de Quarkus.
- **Una diferencia con el RFC:** RFC 8785 trata todo número como un `double`, así que dos identificadores
  enteros que difieren en los últimos dígitos, como `9007199254740992` y `9007199254740993`, darían el mismo
  texto. Un entero que el `double` no representa exacto conserva su valor, para que la misma clave con otro
  identificador sea un 422 y no una respuesta repetida. Los números con parte decimal siguen al RFC sin
  cambios.

## El almacén en memoria

`InMemoryIdempotencyStore` es para desarrollo y pruebas. **Se declara no persistente**
(`persistent()` devuelve `false`): los registros se pierden al reiniciar y no los comparten las réplicas, así
que en producción una operación puede ejecutarse dos veces. Por eso el starter se negará a arrancar con él,
salvo que se pida a propósito con `nova.idempotency.store=memory`.

## El almacén JDBC

`nova-idempotency-jdbc` guarda los registros en PostgreSQL, con JDBC puro. El módulo no trae el driver: lo
trae el servicio.

```kotlin
dependencies {
    implementation("pe.edu.nova.java.libs:nova-idempotency-jdbc:0.1.0")
    runtimeOnly("org.postgresql:postgresql")
}
```

### La tabla

El esquema es un script SQL versionado que viene dentro del módulo, en
`db/nova/idempotency/postgresql/V1__create_idempotency_record.sql`. El servicio lo aplica con su propio
migrador: se copia a sus migraciones con el número de versión que le toque (un servicio con Flyway lo deja,
por ejemplo, en `db/migration/V3__create_idempotency_record.sql`). No se apunta Flyway al script del JAR
porque su `V1` chocaría con la `V1` del servicio.

| Columna | Tipo | Qué guarda |
|---|---|---|
| `scope` | `varchar(255)` | de quién es la clave |
| `idempotency_key` | `varchar(255)` | la clave, tal como llegó |
| `fingerprint` | `varchar(255)` | la huella de la petición que tomó la clave |
| `owner_token` | `varchar(64)` | el token del intento que tiene el lock; nulo cuando ya hay respuesta |
| `status` | `smallint` | el status de la respuesta guardada; nulo mientras la operación corre |
| `headers` | `text[]` | los headers repetibles, como pares nombre y valor en un arreglo plano |
| `body` | `bytea` | el cuerpo de la respuesta, byte por byte |
| `created_at` | `timestamptz` | cuándo tomó la clave el intento dueño de la fila |
| `expires_at` | `timestamptz` | cuándo deja de contar: el lock mientras corre, la retención después |

Una fila es un lock (con dueño y sin respuesta) o un registro completado (sin dueño y con toda la
respuesta), y la restricción `idempotency_record_state` no deja que sea otra cosa. La clave primaria es
`(scope, idempotency_key)`, con colación `"C"`: `k` y `K`, o `k` y `k `, son claves distintas. El índice
`idempotency_record_expires_at` sirve a la purga. Cada columna y la tabla llevan su `COMMENT`.

### Con un `DataSource`

Es la opción simple: cada operación toma su propia conexión y se confirma sola.

```java
IdempotencyStore store = JdbcIdempotencyStore.builder(dataSource).build();
```

El `DataSource` tiene que entregar conexiones con el auto-commit activo, que es el valor por defecto de JDBC
y de HikariCP. Si no, el almacén falla en vez de guardar registros que nunca se confirmarían. El nombre de
la tabla se cambia con `.tableName("orders.idempotency_record")`, pero solo acepta identificadores simples,
porque el nombre va dentro de la sentencia.

### En la misma transacción del negocio

Sin esto, una caída entre el commit del negocio y el cierre del registro vuelve a ejecutar la operación. El
almacén recibe la conexión de quien lo llama, a través de un `ConnectionProvider`:

```java
ConnectionProvider provider = new ConnectionProvider() {
    @Override
    public Connection acquire() {
        return DataSourceUtils.getConnection(dataSource);           // la de la transacción, si hay una abierta
    }

    @Override
    public void release(Connection connection) {
        DataSourceUtils.releaseConnection(connection, dataSource);  // no cierra la de la transacción
    }
};
IdempotencyStore store = JdbcIdempotencyStore.builder(provider).build();
```

El almacén nunca hace commit ni rollback, ni cambia el auto-commit. Un proveedor que entrega la conexión de
la transacción cuando hay una abierta en el hilo, y una nueva cuando no, da el reparto que conviene:

- **`begin` antes de abrir la transacción:** el lock se confirma y queda a la vista, así que un duplicado
  recibe un 409 enseguida.
- **`complete` dentro de la transacción:** la respuesta se confirma en el mismo commit que el pedido. Si la
  transacción se deshace, se deshacen las dos, y el lock vence a su tiempo. Si `complete` devuelve
  `Completion.LOST`, otro intento tomó la clave y conviene deshacer la operación.
- **El latido** que renueva el lock corre en otro hilo, sin transacción, y sigue funcionando.

Si el proveedor entregara siempre la conexión de la transacción, el lock quedaría dentro de ella y nadie lo
vería hasta el commit: una petición repetida esperaría, con el timeout del almacén, y el latido no lo
encontraría. En ese caso se apaga el latido con `withLockRenewal(Duration.ZERO)`. Con el nivel de
aislamiento REPEATABLE READ o SERIALIZABLE, PostgreSQL puede rechazar dos intentos simultáneos con un error
de serialización.

### Cómo se toma la clave

`acquire` es una sola sentencia atómica: `INSERT ... ON CONFLICT DO UPDATE ... WHERE expires_at <= ahora`.
Inserta la clave, o toma la fila si venció, y de varias peticiones simultáneas gana exactamente una. Solo si
pierde lee la fila que encontró. `complete`, `renew` y `release` son un `UPDATE` o un `DELETE` con
`owner_token = ?` en el `WHERE`.

### Los timeouts, la purga y los errores

- **Toda sentencia lleva `setQueryTimeout`**, con el timeout de la configuración redondeado hacia arriba a
  segundos enteros. No cubre la espera de una conexión al pool: eso lo fija el pool.
- **El tiempo se mide con el reloj de la aplicación.** Las réplicas tienen que estar sincronizadas; lo que
  protege el registro es el token de dueño.
- **Los vencidos se purgan por lotes.** `engine.purgeExpired()` borra de a `purge-batch-size` con
  `FOR UPDATE SKIP LOCKED`, sin esperar a una fila que otra transacción está tomando. Conviene correrlo
  desde una tarea programada: un registro vencido ya se ignora y se reutiliza, así que la purga solo libera
  espacio.
- **Un error de la base** dice qué operación falló y con qué SQLState, y **no encadena la `SQLException`**:
  el servidor cita la fila cuando una restricción se rompe, y la fila lleva la clave y el cuerpo.

## Qué queda para la fase 2

El starter de Spring Boot: las propiedades `nova.idempotency.*`, `@Idempotent` por operación, el mapeo a
HTTP con los errores de ADR-031 y el header `Idempotent-Replayed`, la negativa a arrancar sin un almacén
persistente y sin un alcance, y la purga programada. Y su primer consumidor, el servicio de pedidos, que
valida la API para la 1.0.0. Una extensión de Quarkus y un almacén Redis llegan con su primer consumidor.

## Desarrollo

Requiere JDK 25. El build usa el toolchain de Java de Nova: la raíz aplica `pe.edu.nova.java.quality` y
cada módulo `pe.edu.nova.java.library`, que se resuelven desde GitHub Packages, así que Gradle necesita
`GITHUB_ACTOR` y un `GITHUB_TOKEN` con `read:packages`.

```bash
./gradlew build
./gradlew novaFormat
```

`build` corre lo mismo que el CI: el formato, Checkstyle, las pruebas y una cobertura mínima del 80 % de
líneas en cada módulo. `novaFormat` corrige el formato. El primer build instala un hook que valida cada
mensaje de commit con Conventional Commits, y el CI valida los commits de cada PR.

Las pruebas del módulo JDBC levantan un PostgreSQL 17 (`postgres:17.11-alpine`) con Testcontainers, así que
piden Docker. Si la máquina no lo tiene, se saltan, y la cobertura mínima del módulo no se cumple.

**La suite de contrato de `IdempotencyStore`** vive en las pruebas del núcleo (`IdempotencyStoreContract`)
y corre igual contra el almacén en memoria y contra el de JDBC, con un reloj que la prueba adelanta para
vencer un lock o una retención sin esperar. Un almacén nuevo del repositorio, como el de Redis, la extiende
implementando `createStore`.

## Licencia

Eclipse Public License 2.0 — ver [LICENSE](LICENSE).

Copyright © 2026 Angel Hincho.
