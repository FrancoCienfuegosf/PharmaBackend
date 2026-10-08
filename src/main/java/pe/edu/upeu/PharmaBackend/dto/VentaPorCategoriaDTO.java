package pe.edu.upeu.PharmaBackend.dto;

import java.math.BigDecimal;

public record VentaPorCategoriaDTO(
        Long categoriaId,
        String categoriaNombre,
        Long cantidadVendida,
        BigDecimal montoTotal
) {
}
