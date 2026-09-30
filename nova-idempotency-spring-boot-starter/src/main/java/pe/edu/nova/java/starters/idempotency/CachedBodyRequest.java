package pe.edu.nova.java.starters.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Una petición con el cuerpo ya leído entero: la huella lo necesita antes de que corra la operación, y el
 * controlador lo vuelve a leer después, como si nadie lo hubiera tocado.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    /** El cuerpo tal como llegó. */
    byte[] body() {
        return body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        return new BodyStream(body);
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    /** El cuerpo guardado, leído como un stream de servlet que ya tiene todo disponible. */
    private static final class BodyStream extends ServletInputStream {

        private final ByteArrayInputStream source;

        BodyStream(byte[] body) {
            this.source = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return source.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return source.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return source.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("An idempotent request body is already read");
        }
    }
}
