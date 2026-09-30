package pe.edu.nova.java.libs.idempotency;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("pe.edu.nova.java.libs.idempotency");

    @Test
    void theCoreDoesNotDependOnAnyFramework() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "io.quarkus..",
                        "io.micronaut..",
                        "jakarta..",
                        "javax.servlet..",
                        "org.hibernate..",
                        "org.slf4j..",
                        "org.apache.logging..")
                .because("ADR-015: a level 1 library must work in every framework")
                .check(classes);
    }

    @Test
    void theContractDoesNotDependOnItsImplementations() {
        noClasses()
                .that()
                .resideInAPackage("pe.edu.nova.java.libs.idempotency")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "pe.edu.nova.java.libs.idempotency.memory..", "pe.edu.nova.java.libs.idempotency.fingerprint..")
                .because("ADR-047: the ports and the engine work with any store and any fingerprint")
                .check(classes);
    }

    @Test
    void jacksonStaysAnImplementationDetailOfTheCanonicalizer() {
        noClasses()
                .that()
                .haveNameNotMatching(".*[.]JsonCanonicalizer([$].*)?")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.fasterxml.jackson..")
                .because("only JsonCanonicalizer reads JSON, so the parser can change without touching the rest")
                .check(classes);
    }

    @Test
    void nothingWritesToTheConsoleOrUsesJavaUtilLogging() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(classes);
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(classes);
    }
}
