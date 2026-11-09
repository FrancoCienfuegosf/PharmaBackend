package pe.edu.upeu.PharmaBackend.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;

@NoArgsConstructor
@Getter
@Setter
public class ErrorResponseDTO {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private Map<String, String> validationErrors;

    // Codigo de seguimiento de la peticion (el mismo de X-Request-Id y del log).
    // Lo asigna GlobalExceptionHandler a partir de MDC.get("requestId").
    private String requestId;

    // Mismo constructor de 6 parametros que generaba @AllArgsConstructor,
    // para no romper las llamadas existentes en GlobalExceptionHandler.
    public ErrorResponseDTO(LocalDateTime timestamp, int status, String error,
                            String message, String path,
                            Map<String, String> validationErrors) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
        this.validationErrors = validationErrors;
    }
}