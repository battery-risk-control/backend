package com.example.batteryrisk.repository;

import com.example.batteryrisk.domain.MaterialPricePoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface MaterialPriceRepository
        extends JpaRepository<MaterialPricePoint, MaterialPricePoint.Key> {

    /**
     * 공개 가격 추이 패널용 구간 조회. 자재·날짜 오름차순이라 서비스에서 그대로 자재별로 묶고
     * 첫 점을 기준일(=100)로 삼을 수 있다.
     */
    List<MaterialPricePoint> findByPriceDateGreaterThanEqualOrderByMaterialCategoryAscPriceDateAsc(
            LocalDate from);
}
