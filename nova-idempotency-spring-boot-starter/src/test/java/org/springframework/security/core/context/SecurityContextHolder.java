package org.springframework.security.core.context;

/**
 * Un reemplazo vacío de la clase de Spring Security con que el starter detecta que el servicio la usa. La
 * prueba de la auto-configuración la oculta en cada caso salvo en el de Spring Security, que así no necesita
 * traer la dependencia entera.
 */
public final class SecurityContextHolder {

    private SecurityContextHolder() {}
}
