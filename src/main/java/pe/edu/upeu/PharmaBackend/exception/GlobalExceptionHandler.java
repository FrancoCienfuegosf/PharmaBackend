package pe.edu.upeu.PharmaBackend.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pe.edu.upeu.PharmaBackend.dto.ErrorResponseDTO;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /*
    - Arma la respuesta de error y le agrega el codigo de seguimiento.
    - RequestIdFilter dejo el requestId en el MDC del hilo de la peticion;
    - es el mismo valor de la cabecera X-Request-Id y de las lineas del log.
     */
    private ResponseEntity<ErrorResponseDTO> responder(
            HttpStatus status,
            String error,
            String message,
            HttpServletRequest request,
            Map<String, String> validationErrors) {

        ErrorResponseDTO body = new ErrorResponseDTO(
                LocalDateTime.now(),
                status.value(),
                error,
                message,
                request.getRequestURI(),
                validationErrors
        );
        body.setRequestId(MDC.get("requestId"));

        return ResponseEntity
                .status(status)
                .body(body);
    }

    /*
    - Recurso no encontrado
    - HTTP 404
     */
    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<ErrorResponseDTO> handleNotFound(
            RecursoNoEncontradoException ex,
            HttpServletRequest request) {
        log.warn("Recurso no encontrado en {} | {}", request.getRequestURI(), ex.getMessage());
        return responder(HttpStatus.NOT_FOUND, "Not Found",
                ex.getMessage(), request, null);
    }

    /*
    - Regla de negocio
    - HTTP 409
     */
    @ExceptionHandler(ReglaNegocioException.class)
    public ResponseEntity<ErrorResponseDTO> handleBusinessRule(
            ReglaNegocioException ex,
            HttpServletRequest request) {
        log.warn("Regla de negocio en {} | {}", request.getRequestURI(), ex.getMessage());
        return responder(HttpStatus.CONFLICT, "Conflict",
                ex.getMessage(), request, null);
    }

    /*
    - Errores de @Valid
    - HTTP 400
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDTO> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        Map<String, String> validationErrors = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error ->
                        validationErrors.put(
                                error.getField(),
                                error.getDefaultMessage()
                        )
                );

        log.warn("Validacion fallida en {} | campos={}",
                request.getRequestURI(), validationErrors.keySet());
        return responder(HttpStatus.BAD_REQUEST, "Bad Request",
                "Existen errores de validación", request, validationErrors);
    }

    /*
    - Login fallido: usuario inexistente, contraseña incorrecta o
    - usuario deshabilitado. Se responde SIEMPRE el mismo mensaje para
    - no revelar cuál de los datos falló.
    - HTTP 401
    */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponseDTO> handleAuthentication(
            AuthenticationException ex,
            HttpServletRequest request) {
        log.warn("Autenticación fallida en {} | motivo={}",
                request.getRequestURI(),
                ex.getClass().getSimpleName());
        return responder(HttpStatus.UNAUTHORIZED, "Unauthorized",
                "Usuario o contraseña incorrectos", request, null);
    }

    /*
    - @PreAuthorize rechazó la operación dentro del controlador
    - (el usuario está autenticado, pero no tiene el permiso).
    - HTTP 403
    */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponseDTO> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {
        log.warn("Acceso denegado en {}", request.getRequestURI());
        return responder(HttpStatus.FORBIDDEN, "Forbidden",
                "No tiene permisos para realizar esta operación", request, null);
    }

    /*
    - Cualquier otro error no previsto.
    - Las excepciones propias de Spring MVC (metodo no permitido, ruta
    - inexistente, cuerpo ilegible...) conservan su codigo HTTP original.
    - El resto es un error interno: se registra con la traza completa y al
    - cliente solo se le da un mensaje generico mas el codigo de seguimiento.
    - HTTP 500
    */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> handleGeneric(
            Exception ex,
            HttpServletRequest request) {

        if (ex instanceof ErrorResponse er) {
            HttpStatusCode code = er.getStatusCode();
            HttpStatus status = HttpStatus.resolve(code.value());
            if (status == null) {
                status = HttpStatus.INTERNAL_SERVER_ERROR;
            }
            log.warn("Error {} en {} | {}", status.value(),
                    request.getRequestURI(), ex.getClass().getSimpleName());
            return responder(status, status.getReasonPhrase(),
                    "No se pudo procesar la solicitud", request, null);
        }

        log.error("Error no controlado en {}", request.getRequestURI(), ex);
        return responder(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "Ocurrió un error inesperado. Comuníquese con soporte.", request, null);
    }
}