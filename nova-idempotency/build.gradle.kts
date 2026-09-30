plugins {
    id("pe.edu.nova.java.library")
    id("info.solidsoft.pitest")
}

description = "Contrato de la capacidad de idempotencia de Nova, su núcleo y el almacén en memoria."

dependencies {
    // Solo el parser de streaming de Jackson 2, como en nova-secrets: lee el cuerpo JSON para calcular la
    // huella sin atarse al Jackson 3 de Spring Boot 4 ni al Jackson 2 de Quarkus (ADR-042, pregunta abierta 6).
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.3")

    testImplementation("net.jqwik:jqwik:1.9.3")
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
}

// La suite de contrato de IdempotencyStore vive en las pruebas de este módulo, y el módulo JDBC la corre
// contra PostgreSQL. Sale como una variante que solo otro módulo del build puede consumir: no se publica.
val contractTests by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

val contractTestsJar by tasks.registering(Jar::class) {
    archiveClassifier.set("contract-tests")
    from(sourceSets.test.get().output)
}

artifacts {
    add(contractTests.name, contractTestsJar)
}

pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(setOf("pe.edu.nova.java.libs.idempotency.*"))
    targetTests.set(setOf("pe.edu.nova.java.libs.idempotency.*"))
    mutators.set(setOf("DEFAULTS"))
    outputFormats.set(setOf("HTML", "XML"))
    pitestVersion.set("1.17.4")
}
