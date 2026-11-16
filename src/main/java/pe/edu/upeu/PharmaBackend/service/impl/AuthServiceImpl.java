package pe.edu.upeu.PharmaBackend.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;          // ← S14
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.upeu.PharmaBackend.auditoria.EventoAuditoria;               // ← S14
import pe.edu.upeu.PharmaBackend.dto.auth.CambioPasswordRequestDTO;
import pe.edu.upeu.PharmaBackend.dto.auth.LoginRequestDTO;
import pe.edu.upeu.PharmaBackend.dto.auth.LoginResponseDTO;
import pe.edu.upeu.PharmaBackend.dto.auth.UsuarioResponseDTO;
import pe.edu.upeu.PharmaBackend.entity.Usuario;
import pe.edu.upeu.PharmaBackend.exception.RecursoNoEncontradoException;
import pe.edu.upeu.PharmaBackend.exception.ReglaNegocioException;
import pe.edu.upeu.PharmaBackend.mapper.UsuarioMapper;
import pe.edu.upeu.PharmaBackend.repository.UsuarioRepository;
import pe.edu.upeu.PharmaBackend.security.JwtService;
import pe.edu.upeu.PharmaBackend.security.PermisosPorRol;
import pe.edu.upeu.PharmaBackend.service.service.AuditoriaService;        // ← S14
import pe.edu.upeu.PharmaBackend.service.service.AuthService;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log =
            LoggerFactory.getLogger(AuthServiceImpl.class);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UsuarioRepository usuarioRepository;
    private final UsuarioMapper usuarioMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditoriaService auditoriaService;                       // ← S14

    public AuthServiceImpl(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            UsuarioRepository usuarioRepository,
            UsuarioMapper usuarioMapper,
            PasswordEncoder passwordEncoder,
            AuditoriaService auditoriaService) {                           // ← S14
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.usuarioRepository = usuarioRepository;
        this.usuarioMapper = usuarioMapper;
        this.passwordEncoder = passwordEncoder;
        this.auditoriaService = auditoriaService;                          // ← S14
    }

    /*
     * 1. AuthenticationManager busca al usuario (UsuarioDetailsService),
     * verifica que este habilitado y compara la contrasena con el hash
     * usando BCrypt. Si algo falla lanza una AuthenticationException,
     * que GlobalExceptionHandler convierte en 401.
     * 2. Si todo es correcto, se emite el JWT.
     *
     * S14: el login fallido y el exitoso se registran en la bitácora
     * directamente (no por evento): el fallido termina en excepción y no
     * habría commit que esperar. AuditoriaService.registrar usa
     * REQUIRES_NEW, así que se guarda en su propia transacción.
     *
     * La contrasena NUNCA se escribe en el log ni en la bitácora.
     */
    @Override
    @Transactional(readOnly = true)
    public LoginResponseDTO login(LoginRequestDTO request) {

        String username = request.getUsername().trim();

        log.info("Intento de inicio de sesión: usuario={}", username);

        Authentication autenticacion;
        try {                                                              // ← S14
            autenticacion = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.getPassword()));
        } catch (AuthenticationException ex) {
            log.warn("Inicio de sesión fallido: usuario={} motivo={}",
                    username, ex.getClass().getSimpleName());
            auditoriaService.registrar(
                    EventoAuditoria.login("LOGIN_FALLIDO", username));
            throw ex;   // GlobalExceptionHandler sigue respondiendo 401
        }

        UserDetails principal = (UserDetails) autenticacion.getPrincipal();
        Usuario usuario = buscar(principal.getUsername());

        String token = jwtService.generarToken(principal);

        List<String> roles = usuarioMapper.nombresRoles(usuario.getRoles());

        LoginResponseDTO respuesta = new LoginResponseDTO(
                token,
                "Bearer",
                jwtService.getExpiracionSegundos(),
                usuario.getUsername(),
                usuario.getEmpleado().getNombreCompleto(),
                roles,
                PermisosPorRol.permisos(usuario.getRoles())
        );

        // ← S14: se registra al final, cuando ya no puede fallar nada
        auditoriaService.registrar(
                EventoAuditoria.login("LOGIN_EXITOSO", usuario.getUsername()));

        log.info("Inicio de sesión correcto: usuario={} roles={}",
                usuario.getUsername(), roles);

        return respuesta;
    }

    @Override
    @Transactional(readOnly = true)
    public UsuarioResponseDTO usuarioActual(String username) {
        return usuarioMapper.toUsuarioResponse(buscar(username));
    }

    /*
     * Cambio de contraseña del usuario autenticado.
     * El username sale del token (Authentication), nunca del cuerpo.
     *
     * Reglas:
     * - la contraseña actual debe coincidir con el hash (409);
     * - la nueva debe ser distinta de la actual (409);
     * - la nueva no puede superar 72 bytes, el límite de BCrypt (409).
     *
     * Las contraseñas NUNCA se escriben en el log.
     */
    @Override
    @Transactional
    public void cambiarPassword(String username, CambioPasswordRequestDTO request) {

        Usuario usuario = buscar(username);

        if (!passwordEncoder.matches(request.getPasswordActual(), usuario.getPassword())) {
            throw new ReglaNegocioException("La contraseña actual no es correcta");
        }

        if (request.getPasswordNueva().equals(request.getPasswordActual())) {
            throw new ReglaNegocioException("La nueva contraseña debe ser distinta de la actual");
        }

        // BCrypt solo admite 72 BYTES: una ñ o una tilde ocupan 2 bytes en UTF-8
        if (request.getPasswordNueva().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ReglaNegocioException("La contraseña no puede superar 72 bytes");
        }

        usuario.setPassword(passwordEncoder.encode(request.getPasswordNueva()));

        log.info("Contraseña actualizada: usuario={}", usuario.getUsername());
    }

    private Usuario buscar(String username) {
        return usuarioRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "Usuario no encontrado: " + username));
    }
}