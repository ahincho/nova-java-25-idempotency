plugins {
    // La raíz solo agrega módulos. Aplica quality para formatear sus propios scripts e instalar el hook
    // de commits; los módulos aplican library, que trae la compilación, la publicación y OWASP (ADR-044).
    id("pe.edu.nova.java.quality") version "1.3.1"
    id("pe.edu.nova.java.library") version "1.3.1" apply false
    id("net.nemerosa.versioning") version "4.0.1"
    id("info.solidsoft.pitest") version "1.19.0-rc.1" apply false
}

versioning {
    releaseMode = "snapshot"
    displayMode = "snapshot"
    releaseBuild = false
}

subprojects {
    // El contrato y los almacenes son librerías puras (nivel 1 de ADR-001); los conectores de un
    // framework son nivel 2. Cada nivel tiene su groupId (ADR-004), aunque vivan en el mismo
    // repositorio y salgan con la misma versión (ADR-041).
    group =
        if (name.endsWith("-spring-boot-starter") || name.endsWith("-quarkus-extension")) {
            "pe.edu.nova.java.starters"
        } else {
            "pe.edu.nova.java.libs"
        }
    version = rootProject.findProperty("version") as String
}
