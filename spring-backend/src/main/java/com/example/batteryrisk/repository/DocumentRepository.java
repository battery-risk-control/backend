package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, String> {
    Optional<Document> findByContractIdAndContentHash(Long contractId, String contentHash);

    /** 계약의 가장 최근 문서. 재업로드 시 이 문서의 document_id를 재사용해 내용을 교체한다. */
    Optional<Document> findFirstByContractIdOrderByCreatedAtDesc(Long contractId);

    /** 계약의 모든 문서. 교체 후 남은 옛 형제 문서들을 정리하는 데 쓴다. */
    List<Document> findByContractId(Long contractId);

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
