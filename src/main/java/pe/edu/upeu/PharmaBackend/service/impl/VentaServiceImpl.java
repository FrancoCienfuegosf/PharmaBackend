package pe.edu.upeu.PharmaBackend.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;            // ← S14
import org.springframework.data.domain.Page;                             // ← S14 pag
import org.springframework.data.domain.PageRequest;                      // ← S14 pag
import org.springframework.data.domain.Pageable;                         // ← S14 pag
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.upeu.PharmaBackend.auditoria.EventoAuditoria;             // ← S14
import pe.edu.upeu.PharmaBackend.dto.DetalleVentaRequestDTO;
import pe.edu.upeu.PharmaBackend.dto.DetalleVentaResponseDTO;
import pe.edu.upeu.PharmaBackend.dto.PaginaResponseDTO;                  // ← S14 pag
import pe.edu.upeu.PharmaBackend.dto.VentaRequestDTO;
import pe.edu.upeu.PharmaBackend.dto.VentaResponseDTO;
import pe.edu.upeu.PharmaBackend.entity.Cliente;
import pe.edu.upeu.PharmaBackend.entity.DetalleVenta;
import pe.edu.upeu.PharmaBackend.entity.Producto;
import pe.edu.upeu.PharmaBackend.entity.Venta;
import pe.edu.upeu.PharmaBackend.enums.EstadoVenta;
import pe.edu.upeu.PharmaBackend.exception.RecursoNoEncontradoException;
import pe.edu.upeu.PharmaBackend.exception.ReglaNegocioException;
import pe.edu.upeu.PharmaBackend.repository.ClienteRepository;
import pe.edu.upeu.PharmaBackend.repository.ProductoRepository;
import pe.edu.upeu.PharmaBackend.repository.VentaRepository;
import pe.edu.upeu.PharmaBackend.service.service.VentaService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

@Service
public class VentaServiceImpl implements VentaService {
    private static final Logger log = LoggerFactory.getLogger(VentaServiceImpl.class);

    private final VentaRepository ventaRepository;
    private final ClienteRepository clienteRepository;
    private final ProductoRepository productoRepository;
    private final ApplicationEventPublisher eventos;                      // ← S14

    private static final Map<String, String> CAMPOS_ORDENABLES = Map.of(
            "id", "id",
            "fecha", "fechaRegistro",
            "total", "total",
            "estado", "estado");
    private static final String ORDEN_POR_DEFECTO = "fecha";
    private static final int TAMANIO_MAXIMO = 100;                        // ← S14 pag

    public VentaServiceImpl(
            VentaRepository ventaRepository,
            ClienteRepository clienteRepository,
            ProductoRepository productoRepository,
            ApplicationEventPublisher eventos) {                          // ← S14

        this.ventaRepository = ventaRepository;
        this.clienteRepository = clienteRepository;
        this.productoRepository = productoRepository;
        this.eventos = eventos;                                           // ← S14
    }

    @Override
    @Transactional
    // la venta descuenta stock: los productos cacheados quedan desactualizados
    @CacheEvict(cacheNames = "productos", allEntries = true)
    public VentaResponseDTO registrar(VentaRequestDTO request) {
        Cliente cliente = clienteRepository.findById(request.getClienteId())
                .orElseThrow(() -> new RecursoNoEncontradoException("Cliente no encontrado con id: " + request.getClienteId()));

        if (!Boolean.TRUE.equals(cliente.getEstado())) {
            throw new ReglaNegocioException("No se puede registrar una venta para un cliente inactivo");
        }
        Venta venta = new Venta();

        venta.setCliente(cliente);
        venta.setFechaRegistro(LocalDateTime.now());
        venta.setEstado(EstadoVenta.REGISTRADA);

        BigDecimal total = BigDecimal.ZERO;

        for (DetalleVentaRequestDTO item : request.getDetalles()) {
            Producto producto = productoRepository.findById(item.getProductoId()).orElseThrow(() ->
                    new RecursoNoEncontradoException("Producto no encontrado con id: " + item.getProductoId()));

            if (!Boolean.TRUE.equals(producto.getEstado())) {
                throw new ReglaNegocioException("El producto " + producto.getNombre() + " se encuentra inactivo");
            }

            if (producto.getStock() < item.getCantidad()) {

                throw new ReglaNegocioException("Stock insuficiente para " + producto.getNombre() + ". Disponible: " + producto.getStock()
                        + ", solicitado: " + item.getCantidad());
            }

            BigDecimal subtotal = producto.getPrecio().multiply(BigDecimal.valueOf(item.getCantidad()));

            DetalleVenta detalle = new DetalleVenta();

            detalle.setProducto(producto);
            detalle.setCantidad(item.getCantidad());
            detalle.setPrecio(producto.getPrecio());
            detalle.setSubtotal(subtotal);

            venta.agregarDetalle(detalle);

            total = total.add(subtotal);

            producto.setStock(producto.getStock() - item.getCantidad());
        }

        venta.setTotal(total);

        Venta guardada = ventaRepository.save(venta);

        // ← S14: se registra en la bitácora solo cuando la transacción hace commit
        eventos.publishEvent(EventoAuditoria.de("VENTA_REGISTRADA", "Venta",
                guardada.getId(),
                "total=" + guardada.getTotal()
                        + " | items=" + guardada.getDetalles().size()));

        log.info("Venta registrada | id={} | clienteId={} | total={} | items={}",  // ← S14
                guardada.getId(), cliente.getId(), guardada.getTotal(),
                guardada.getDetalles().size());

        return convertirResponse(guardada);
    }

    @Override
    @Transactional(readOnly = true)
    public VentaResponseDTO buscar(Long id) {

        Venta venta = ventaRepository.findById(id).orElseThrow(() ->
                new RecursoNoEncontradoException("Venta no encontrada con id: " + id));
        return convertirResponse(venta);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VentaResponseDTO> listar() {
        return ventaRepository.findAll().stream().map(this::convertirResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PaginaResponseDTO<VentaResponseDTO> buscarVentas(              // ← S14 pag
                                                                          Long clienteId,
                                                                          EstadoVenta estado,
                                                                          LocalDate desde,
                                                                          LocalDate hasta,
                                                                          String ordenarPor,
                                                                          String direccion,
                                                                          int pagina,                                                   // ← S14 pag
                                                                          int tamanio) {                                                // ← S14 pag

        long inicio = System.currentTimeMillis();

        log.info("Inicio buscar ventas | clienteId={} | estado={} | "
                        + "desde={} | hasta={} | ordenarPor={} | direccion={} | "
                        + "pagina={} | tamanio={}",
                clienteId, estado, desde, hasta, ordenarPor, direccion,
                pagina, tamanio);

        if (desde != null
                && hasta != null
                && desde.isAfter(hasta)) {

            throw new ReglaNegocioException(
                    "El rango de fechas es inválido: 'desde' ("
                            + desde
                            + ") es posterior a 'hasta' ("
                            + hasta + ")");
        }

        // ← S14 pag: rechazar valores fuera de rango (409)
        if (pagina < 0) {
            throw new ReglaNegocioException("La página no puede ser negativa");
        }
        if (tamanio < 1 || tamanio > TAMANIO_MAXIMO) {
            throw new ReglaNegocioException(
                    "El tamaño de página debe estar entre 1 y " + TAMANIO_MAXIMO);
        }

        // ← S14 pag: desempate por id para que ninguna venta se repita entre páginas
        Sort sort = construirSort(ordenarPor, direccion).and(Sort.by("id").descending());
        Pageable pageable = PageRequest.of(pagina, tamanio, sort);

        LocalDateTime desdeHora = (desde == null)
                ? null
                : desde.atStartOfDay();

        LocalDateTime hastaHora = (hasta == null)
                ? null
                : hasta.atTime(LocalTime.MAX);

        Page<VentaResponseDTO> resultado =                                // ← S14 pag
                ventaRepository
                        .buscar(clienteId, estado, desdeHora, hastaHora, pageable)
                        .map(this::convertirResponse);

        log.info("Fin buscar ventas | clienteId={} | estado={} | "
                        + "desde={} | hasta={} | orden={} {} | "
                        + "pagina={} | filas={} | total={} | duracionMs={}",
                clienteId, estado, desde, hasta, ordenarPor, direccion,
                pagina,
                resultado.getNumberOfElements(),
                resultado.getTotalElements(),
                System.currentTimeMillis() - inicio);

        return PaginaResponseDTO.de(resultado);
    }

    private Sort construirSort(String ordenarPor, String direccion) {

        String campo = (ordenarPor == null || ordenarPor.isBlank())
                ? ORDEN_POR_DEFECTO
                : ordenarPor.trim();

        if (!CAMPOS_ORDENABLES.containsKey(campo)) {

            throw new ReglaNegocioException(
                    "El campo de ordenamiento '"
                            + campo
                            + "' no está permitido. Campos válidos: "
                            + CAMPOS_ORDENABLES.keySet());
        }

        String propiedad = CAMPOS_ORDENABLES.get(campo);

        String sentido = (direccion == null || direccion.isBlank())
                ? "desc"
                : direccion.trim();

        if (!sentido.equalsIgnoreCase("asc")
                && !sentido.equalsIgnoreCase("desc")) {

            throw new ReglaNegocioException(
                    "La dirección de ordenamiento '"
                            + sentido
                            + "' no está permitida. Valores válidos: asc, desc");
        }

        return sentido.equalsIgnoreCase("asc")
                ? Sort.by(propiedad).ascending()
                : Sort.by(propiedad).descending();
    }

    private VentaResponseDTO convertirResponse(Venta venta) {

        List<DetalleVentaResponseDTO> detalles =
                venta.getDetalles()
                        .stream()
                        .map(detalle ->
                                new DetalleVentaResponseDTO(
                                        detalle.getProducto().getId(),
                                        detalle.getProducto().getNombre(),
                                        detalle.getCantidad(),
                                        detalle.getPrecio(),
                                        detalle.getSubtotal()
                                )
                        ).toList();

        String clienteNombre = venta.getCliente().getNombres() + " " + venta.getCliente().getApellidos();

        return new VentaResponseDTO(
                venta.getId(),
                venta.getFechaRegistro(),
                venta.getCliente().getId(),
                clienteNombre,
                venta.getEstado().name(),
                venta.getTotal(),
                detalles
        );
    }
}