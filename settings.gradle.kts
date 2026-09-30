pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // El toolchain de Nova se publica en GitHub Packages, que pide credenciales incluso para leer.
        // En CI llega el token del workflow; en local alcanza un GITHUB_TOKEN con read:packages.
        maven {
            name = "NovaGradleToolchain"
            url = uri("https://maven.pkg.github.com/ahincho/nova-java-24-gradle-toolchain")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("NOVA_PACKAGES_READ_TOKEN") ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// La familia de la capacidad de idempotencia (ADR-041): la raíz solo agrega módulos y no se publica.
rootProject.name = "nova-idempotency"

include("nova-idempotency")
include("nova-idempotency-jdbc")
include("nova-idempotency-spring-boot-starter")
