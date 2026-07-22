package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
    Optional<Document> findByContractIdAndContentHash(Long contractId, String contentHash);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM contracts
                WHERE contract_id = :contractId AND supplier_id = :supplierId
            )
            """, nativeQuery = true)
    boolean existsContractSupplier(
            @Param("contractId") Long contractId,
            @Param("supplierId") Long supplierId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM materials WHERE material_id = :materialId)", nativeQuery = true)
    boolean existsMaterial(@Param("materialId") Long materialId);
}
