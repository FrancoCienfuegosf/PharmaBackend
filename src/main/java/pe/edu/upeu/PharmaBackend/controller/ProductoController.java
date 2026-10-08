package pe.edu.upeu.PharmaBackend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.upeu.PharmaBackend.dto.ProductoRequestDTO;
import pe.edu.upeu.PharmaBackend.dto.ProductoResponseDTO;
import pe.edu.upeu.PharmaBackend.service.service.ProductoService;

@RestController
@RequestMapping("/api/v1/productos")
public class ProductoController {

    private final ProductoService productoService;

    public ProductoController(ProductoService productoService) {
        this.productoService = productoService;
    }

    @GetMapping
    public ResponseEntity<Iterable<ProductoResponseDTO>> findAll() {
        return ResponseEntity.ok(productoService.readAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductoResponseDTO> findById(
            @PathVariable Long id) {

        return ResponseEntity.ok(productoService.read(id));
    }

    @PostMapping
    public ResponseEntity<ProductoResponseDTO> create(
            @Valid @RequestBody ProductoRequestDTO requestDTO) {

        ProductoResponseDTO response =
                productoService.create(requestDTO);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProductoResponseDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody ProductoRequestDTO requestDTO) {

        return ResponseEntity.ok(
                productoService.update(id, requestDTO)
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productoService.delete(id);
        return ResponseEntity.noContent().build();
    }
}