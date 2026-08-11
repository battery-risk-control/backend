package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.OutboundDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OutboundDocumentRepository extends JpaRepository<OutboundDocument, String> {
    Optional<OutboundDocument> findByOutboundContractIdAndContentHash(Long outboundContractId, String contentHash);

    /** 아웃바운드 계약의 가장 최근 문서. 재업로드 시 이 문서의 document_id를 재사용해 내용을 교체한다. */
    Optional<OutboundDocument> findFirstByOutboundContractIdOrderByCreatedAtDesc(Long outboundContractId);

    /** 아웃바운드 계약의 모든 문서. 교체 후 남은 옛 형제 문서들을 정리하는 데 쓴다. */
    List<OutboundDocument> findByOutboundContractId(Long outboundContractId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM outbound_contracts WHERE outbound_contract_id = :outboundContractId)",
            nativeQuery = true)
    boolean existsOutboundContract(@Param("outboundContractId") Long outboundContractId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM products WHERE product_id = :productId)", nativeQuery = true)
    boolean existsProduct(@Param("productId") Long productId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM customers WHERE customer_id = :customerId)", nativeQuery = true)
    boolean existsCustomer(@Param("customerId") Long customerId);
}
