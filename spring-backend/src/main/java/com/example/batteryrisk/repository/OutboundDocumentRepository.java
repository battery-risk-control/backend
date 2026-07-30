package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.OutboundDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OutboundDocumentRepository extends JpaRepository<OutboundDocument, String> {
    Optional<OutboundDocument> findByOutboundContractIdAndContentHash(Long outboundContractId, String contentHash);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM outbound_contracts WHERE outbound_contract_id = :outboundContractId)",
            nativeQuery = true)
    boolean existsOutboundContract(@Param("outboundContractId") Long outboundContractId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM products WHERE product_id = :productId)", nativeQuery = true)
    boolean existsProduct(@Param("productId") Long productId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM customers WHERE customer_id = :customerId)", nativeQuery = true)
    boolean existsCustomer(@Param("customerId") Long customerId);
}
