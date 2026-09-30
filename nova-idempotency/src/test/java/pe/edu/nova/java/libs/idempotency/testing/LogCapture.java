package pe.edu.nova.java.libs.idempotency.testing;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Recoge todo lo que la capacidad escribe en el log mientras está abierta: el mensaje ya armado, los
 * parámetros y la excepción con su causa y su traza.
 *
 * <p>{@code System.Logger} escribe en {@code java.util.logging} cuando ningún puente lo desvía, así que
 * basta con un handler en el logger de la capacidad. Sirve para comprobar que la clave y el cuerpo nunca
 * aparecen en un log.
 */
public final class LogCapture implements AutoCloseable {

    private static final String LOGGER = "pe.edu.nova.java.libs.idempotency";

    // Se guarda una referencia fuerte: java.util.logging retiene sus loggers de forma débil.
    private final Logger logger = Logger.getLogger(LOGGER);
    private final Level previousLevel = logger.getLevel();
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // No hay nada que vaciar: los registros quedan en memoria.
        }

        @Override
        public void close() {
            // No hay nada que cerrar.
        }
    };

    /** Empieza a recoger, en todos los niveles. */
    public LogCapture() {
        logger.setLevel(Level.ALL);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    /**
     * Cada registro como texto: el mensaje armado, los parámetros y la excepción completa.
     *
     * @return una entrada por registro
     */
    public List<String> entries() {
        SimpleFormatter formatter = new SimpleFormatter();
        List<String> entries = new ArrayList<>();
        for (LogRecord record : records) {
            StringBuilder entry =
                    new StringBuilder(record.getLevel().getName()).append(' ').append(formatter.formatMessage(record));
            if (record.getParameters() != null) {
                entry.append(' ').append(List.of(record.getParameters()));
            }
            if (record.getThrown() != null) {
                StringWriter trace = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(trace));
                entry.append('\n').append(trace);
            }
            entries.add(entry.toString());
        }
        return entries;
    }

    /**
     * Todo lo recogido, en un solo texto.
     *
     * @return el texto
     */
    public String text() {
        return String.join("\n", entries());
    }

    /**
     * Los registros que contienen un texto.
     *
     * @param fragment lo que se busca
     * @return las entradas que lo contienen
     */
    public List<String> entriesContaining(String fragment) {
        return entries().stream().filter(entry -> entry.contains(fragment)).toList();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(previousLevel);
    }
}
