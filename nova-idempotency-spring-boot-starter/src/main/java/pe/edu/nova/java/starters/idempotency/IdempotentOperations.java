package pe.edu.nova.java.starters.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Dice si una petición va a una operación con {@link Idempotent}. Busca el método del controlador con el mismo
 * {@link RequestMappingHandlerMapping} que usa Spring MVC, antes de que la petición llegue a él.
 *
 * <p>Solo mira los métodos que cambian estado: POST, PUT, PATCH y DELETE. Un GET ya es idempotente.
 */
final class IdempotentOperations {

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    IdempotentOperations(ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.mappings = mappings;
    }

    boolean isIdempotent(HttpServletRequest request) {
        if (!UNSAFE_METHODS.contains(request.getMethod())) {
            return false;
        }
        // La búsqueda escribe atributos en la petición, como la ruta ya parseada o las variables de la
        // plantilla; se escriben aparte para que Spring MVC arranque de cero con la petición real.
        HttpServletRequest isolated = new IsolatedAttributes(request);
        ServletRequestPathUtils.parseAndCache(isolated);
        for (RequestMappingHandlerMapping mapping :
                (Iterable<RequestMappingHandlerMapping>) mappings.orderedStream()::iterator) {
            HandlerExecutionChain chain;
            try {
                chain = mapping.getHandler(isolated);
            } catch (Exception noMatch) {
                // Un método no soportado o un tipo de contenido que no acepta: lo responde Spring MVC.
                continue;
            }
            if (chain != null && chain.getHandler() instanceof HandlerMethod method) {
                return method.hasMethodAnnotation(Idempotent.class)
                        || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), Idempotent.class);
            }
        }
        return false;
    }

    /** Una petición cuyos atributos nuevos o borrados quedan en ella y no llegan a la original. */
    private static final class IsolatedAttributes extends HttpServletRequestWrapper {

        private final Map<String, Object> written = new HashMap<>();
        private final Set<String> removed = new HashSet<>();

        IsolatedAttributes(HttpServletRequest request) {
            super(request);
        }

        @Override
        public Object getAttribute(String name) {
            if (written.containsKey(name)) {
                return written.get(name);
            }
            return removed.contains(name) ? null : super.getAttribute(name);
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getAttributeNames()));
            names.removeAll(removed);
            names.addAll(written.keySet());
            return Collections.enumeration(names);
        }

        @Override
        public void setAttribute(String name, Object value) {
            if (value == null) {
                removeAttribute(name);
                return;
            }
            written.put(name, value);
            removed.remove(name);
        }

        @Override
        public void removeAttribute(String name) {
            written.remove(name);
            removed.add(name);
        }
    }
}
