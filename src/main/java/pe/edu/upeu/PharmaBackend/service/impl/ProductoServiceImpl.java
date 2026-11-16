package pe.edu.upeu.PharmaBackend.service.impl;

import java.math.BigDecimal;                                               // ← S14
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;              // ← S14
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.upeu.PharmaBackend.auditoria.EventoAuditoria;               // ← S14
import pe.edu.upeu.PharmaBackend.dto.PaginaResponseDTO;
import pe.edu.upeu.PharmaBackend.dto.ProductoRequestDTO;
import pe.edu.upeu.PharmaBackend.dto.ProductoResponseDTO;
import pe.edu.upeu.PharmaBackend.entity.Categoria;
import pe.edu.upeu.PharmaBackend.entity.Producto;
import pe.edu.upeu.PharmaBackend.exception.RecursoNoEncontradoException;
import pe.edu.upeu.PharmaBackend.exception.ReglaNegocioException;
import pe.edu.upeu.PharmaBackend.repository.CategoriaRepository;
import pe.edu.upeu.PharmaBackend.repository.ProductoRepository;
import pe.edu.upeu.PharmaBackend.service.service.ProductoService;


@Service
public class ProductoServiceImpl implements ProductoService {
    private static final Logger LOG = LoggerFactory.getLogger(ProductoServiceImpl.class);

    private static final Set<String> CAMPOS_ORDEN = Set.of("id", "nombre", "precio", "stock");
    private static final int TAMANIO_MAXIMO = 100;                         // ← S14

    private final ProductoRepository productoRepository;
    private final CategoriaRepository categoriaRepository;
    private final ApplicationEventPublisher eventos;                       // ← S14

    public ProductoServiceImpl(ProductoRepository productoRepository,
                               CategoriaRepository categoriaRepository,
                               ApplicationEventPublisher eventos) {        // ← S14
        this.productoRepository = productoRepository;
        this.categoriaRepository = categoriaRepository;
        this.eventos = eventos;                                            // ← S14
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "productos", allEntries = true)
    public ProductoResponseDTO create(ProductoRequestDTO t) {
        String nombre = t.getNombre().trim();
        if(productoRepository.existsByNombreIgnoreCase(nombre)){
            throw new ReglaNegocioException(
                    "Ya existe un producto con el nombre "+ nombre
            );
        }

        Categoria categoria = buscarCategoria(t.getCategoriaId());

        Producto producto = new Producto();
        producto.setNombre(nombre);
        producto.setPrecio(t.getPrecio());
        producto.setStock(t.getStock());
        producto.setEstado(t.getEstado());
        producto.setCategoria(categoria);

        Producto prodCreado = productoRepository.save(producto);

        return convertirResponse(prodCreado);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "productos", allEntries = true)
    public ProductoResponseDTO update(Long aLong, ProductoRequestDTO t) {
        Producto producto = productoRepository.findById(aLong).orElseThrow(()->
                new RecursoNoEncontradoException(
                        "Producto no encontrado con id: "+ aLong
                )
        );

        String nombre = t.getNombre().trim();
        if(productoRepository.existsByNombreIgnoreCaseAndIdNot(nombre, aLong)){
            throw new ReglaNegocioException(
                    "Ya existe un producto con el nombre "+ nombre
            );
        }

        Categoria categoria = buscarCategoria(t.getCategoriaId());

        BigDecimal precioAnterior = producto.getPrecio();                  // ← S14: ANTES de modificar

        producto.setNombre(nombre);
        producto.setPrecio(t.getPrecio());
        producto.setStock(t.getStock());
        producto.setEstado(t.getEstado());
        producto.setCategoria(categoria);

        Producto prodActualizado = productoRepository.saveAndFlush(producto);

        // ← S14: solo si el precio realmente cambió (compareTo ignora 10.0 vs 10.00)
        if (precioAnterior.compareTo(prodActualizado.getPrecio()) != 0) {
            eventos.publishEvent(EventoAuditoria.de("PRECIO_MODIFICADO", "Producto",
                    prodActualizado.getId(),
                    prodActualizado.getNombre() + ": "
                            + precioAnterior + " -> " + prodActualizado.getPrecio()));
        }

        return convertirResponse(prodActualizado);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "productos", key = "'id-' + #aLong")
    public ProductoResponseDTO read(Long aLong) {
        Producto producto = productoRepository.findById(aLong)
                .orElseThrow(()->
                        new RecursoNoEncontradoException(
                                "Producto no encontrado con id: "+ aLong
                        )
                );
        return convertirResponse(producto);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "productos", allEntries = true)
    public void delete(Long aLong) {
        Producto producto = productoRepository.findById(aLong).orElseThrow(()->
                new RecursoNoEncontradoException(
                        "Producto no encontrado con id: "+ aLong
                )
        );

        if (Boolean.FALSE.equals(producto.getEstado())) {
            throw new ReglaNegocioException("El producto ya está inactivo");
        }

        producto.setEstado(false);
        productoRepository.save(producto);

        // ← S14: eliminación lógica, queda quién la hizo
        eventos.publishEvent(EventoAuditoria.de("PRODUCTO_ELIMINADO", "Producto",
                producto.getId(), producto.getNombre()));
    }

    @Override
    @Transactional(readOnly = true)
    public Iterable<ProductoResponseDTO> readAll() {
        return productoRepository.findAll()
                .stream()
                .map(this::convertirResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "productos",
            key = "#pagina + '-' + #tamanio + '-' + #ordenarPor + '-' + #direccion")
    public PaginaResponseDTO<ProductoResponseDTO> listar(
            int pagina, int tamanio, String ordenarPor, String direccion) {

        // ← S14: rechazar valores fuera de rango (409) en vez de corregirlos en silencio
        if (pagina < 0) {
            throw new ReglaNegocioException("La página no puede ser negativa");
        }
        if (tamanio < 1 || tamanio > TAMANIO_MAXIMO) {
            throw new ReglaNegocioException(
                    "El tamaño de página debe estar entre 1 y " + TAMANIO_MAXIMO);
        }

        if (!CAMPOS_ORDEN.contains(ordenarPor)) {
            throw new ReglaNegocioException(
                    "No se puede ordenar por " + ordenarPor
                            + ". Use: id, nombre, precio o stock");
        }

        Sort.Direction dir = "desc".equalsIgnoreCase(direccion)
                ? Sort.Direction.DESC : Sort.Direction.ASC;

        // ← S14: desempate por id para que el orden sea estable entre páginas
        Pageable pageable = PageRequest.of(
                pagina, tamanio, Sort.by(dir, ordenarPor).and(Sort.by("id")));

        Page<ProductoResponseDTO> resultado = productoRepository
                .findAll(pageable)
                .map(this::convertirResponse);

        return PaginaResponseDTO.de(resultado);
    }

    private Categoria buscarCategoria(Long categoriaId){
        return categoriaRepository.findById(categoriaId).orElseThrow(()->
                new RecursoNoEncontradoException(
                        "Categoria no encontrada con id: "+ categoriaId
                )
        );
    }

    private ProductoResponseDTO convertirResponse(Producto producto){
        return new ProductoResponseDTO(
                producto.getId(),
                producto.getNombre(),
                producto.getPrecio(),
                producto.getStock(),
                producto.getEstado(),
                producto.getCategoria().getId(),
                producto.getCategoria().getNombre(),
                producto.getFechaCreacion(),
                producto.getFechaModificacion(),
                producto.getCreadoPor(),                                   // ← S14
                producto.getModificadoPor()                                // ← S14
        );
    }
}