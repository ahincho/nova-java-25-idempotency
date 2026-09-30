# nova-java-25-idempotency

La capacidad de idempotencia de Nova Platform. Un cliente puede reintentar una operación sin que
se ejecute dos veces: la petición lleva un `Idempotency-Key`, y el servicio responde al reintento
con la respuesta que ya dio, en vez de repetir la operación.

Las decisiones están en [ADR-047](https://github.com/ahincho/nova-shared-01-docs/blob/main/adrs/shared/ADR-047-idempotencia-detras-de-un-contrato.md),
y la forma del repositorio —un contrato y todas sus implementaciones juntos, con una sola
versión— en [ADR-041](https://github.com/ahincho/nova-shared-01-docs/blob/main/adrs/java/ADR-041-un-repositorio-por-capacidad.md).

## Módulos

| Módulo | `groupId` | Qué es | Estado |
|---|---|---|---|
| `nova-idempotency` | `pe.edu.nova.java.libs` | el contrato, sin frameworks ni proveedores, su núcleo y el almacén en memoria | planificado |
| `nova-idempotency-jdbc` | `pe.edu.nova.java.libs` | el almacén JDBC, para PostgreSQL | planificado |
| `nova-idempotency-spring-boot-starter` | `pe.edu.nova.java.starters` | conecta la capacidad con Spring Boot | planificado, en la fase 2 |

Todos se publican en `https://maven.pkg.github.com/ahincho/nova-java-25-idempotency` con la misma
versión.

## Licencia

Eclipse Public License 2.0 — ver [LICENSE](LICENSE).

Copyright © 2026 Angel Hincho.
