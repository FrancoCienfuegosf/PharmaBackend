package pe.edu.upeu.PharmaBackend.repository;

import org.springframework.data.domain.Page;                              // ← S14 pag
import org.springframework.data.domain.Pageable;                          // ← S14 pag
import org.springframework.data.jpa.repository.EntityGraph;               // ← S14 pag
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.upeu.PharmaBackend.dto.ProductoMasVendidoDTO;
import pe.edu.upeu.PharmaBackend.dto.VentaPorCategoriaDTO;
import pe.edu.upeu.PharmaBackend.entity.Venta;
import pe.edu.upeu.PharmaBackend.enums.EstadoVenta;

import java.time.LocalDateTime;
import java.util.List;

public interface VentaRepository extends JpaRepository<Venta, Long> {

    /*
     * S14: búsqueda paginada en el servidor.
     * - Sin "join fetch" de colecciones: con paginación, Hibernate tendría
     *   que traer todo y paginar en memoria (HHH90003004).
     * - El cliente (ManyToOne) se trae en la misma consulta con @EntityGraph.
     * - Los detalles y sus productos se cargan por lotes
     *   (hibernate.default_batch_fetch_size), no uno por venta.
     * - countQuery explícita: cuenta solo ventas, sin joins innecesarios.
     */
    @EntityGraph(attributePaths = "cliente")
    @Query(value = """
            select v
            from Venta v
            where (:clienteId is null or v.cliente.id = :clienteId)
              and (:estado    is null or v.estado = :estado)
              and (:desde     is null or v.fechaRegistro >= :desde)
              and (:hasta     is null or v.fechaRegistro <= :hasta)
            """,
            countQuery = """
            select count(v)
            from Venta v
            where (:clienteId is null or v.cliente.id = :clienteId)
              and (:estado    is null or v.estado = :estado)
              and (:desde     is null or v.fechaRegistro >= :desde)
              and (:hasta     is null or v.fechaRegistro <= :hasta)
            """)
    Page<Venta> buscar(
            @Param("clienteId") Long clienteId,
            @Param("estado") EstadoVenta estado,
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta,
            Pageable pageable);

    @Query("""
            select new pe.edu.upeu.PharmaBackend.dto.VentaPorCategoriaDTO(
                       cat.id,
                       cat.nombre,
                       sum(d.cantidad),
                       sum(d.subtotal))
            from DetalleVenta d
            join d.venta v
            join d.producto p
            join p.categoria cat
            where v.estado = pe.edu.upeu.PharmaBackend.enums.EstadoVenta.REGISTRADA
              and (:desde is null or v.fechaRegistro >= :desde)
              and (:hasta is null or v.fechaRegistro <= :hasta)
            group by cat.id, cat.nombre
            order by sum(d.subtotal) desc
            """)
    List<VentaPorCategoriaDTO> reporteVentasPorCategoria(
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);

    @Query("""
            select new pe.edu.upeu.PharmaBackend.dto.ProductoMasVendidoDTO(
                       p.id,
                       p.nombre,
                       cat.nombre,
                       sum(d.cantidad),
                       sum(d.subtotal))
            from DetalleVenta d
            join d.venta v
            join d.producto p
            join p.categoria cat
            where v.estado = pe.edu.upeu.PharmaBackend.enums.EstadoVenta.REGISTRADA
              and (:desde is null or v.fechaRegistro >= :desde)
              and (:hasta is null or v.fechaRegistro <= :hasta)
            group by p.id, p.nombre, cat.nombre
            order by sum(d.cantidad) desc
            """)
    List<ProductoMasVendidoDTO> reporteProductosMasVendidos(
            @Param("desde") LocalDateTime desde,
            @Param("hasta") LocalDateTime hasta);
}