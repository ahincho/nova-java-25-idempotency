plugins {
    id("pe.edu.nova.java.library")
}

description = "Almacén de la capacidad de idempotencia para PostgreSQL, con JDBC puro."

dependencies {
    api(project(":nova-idempotency"))

    // La suite de contrato del almacén, que vive en las pruebas del núcleo, corre aquí contra PostgreSQL.
    testImplementation(project(path = ":nova-idempotency", configuration = "contractTests"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // El driver solo lo necesitan las pruebas: el almacén habla JDBC y cada servicio trae el suyo.
    testImplementation("org.postgresql:postgresql:42.7.13")
}
