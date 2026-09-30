plugins {
    id("pe.edu.nova.java.library")
}

description = "Conector de la capacidad de idempotencia con Spring Boot: @Idempotent, el almacén y los errores HTTP."

val springBootVersion = "4.0.8"
val springFrameworkVersion = "7.0.9"

repositories {
    // El sobre de los errores es el de nova-api-standard, que se publica en el GitHub Packages de su repositorio.
    maven {
        name = "NovaApiStandard"
        url = uri("https://maven.pkg.github.com/ahincho/nova-java-01-api-standard")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("NOVA_PACKAGES_READ_TOKEN") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    api(project(":nova-idempotency"))
    // El almacén por defecto de un servicio con base de datos. No trae dependencias: solo usa java.sql.
    api(project(":nova-idempotency-jdbc"))
    api("pe.edu.nova.java.libs:nova-api-standard:1.0.2")

    // Spring Boot lo trae el servicio; el starter solo compila contra él. Van los módulos y no los starters,
    // que traen Tomcat y HikariCP: el análisis de OWASP recorre el classpath de compilación.
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:$springBootVersion")
    compileOnly("org.springframework.boot:spring-boot-webmvc:$springBootVersion")
    compileOnly("org.springframework.boot:spring-boot-jackson:$springBootVersion")
    compileOnly("org.springframework:spring-jdbc:$springFrameworkVersion")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:$springBootVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc:$springBootVersion")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc:$springBootVersion")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.postgresql:postgresql:42.7.13")
}
