package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.MaterialMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MaterialMappingRepository extends JpaRepository<MaterialMapping, Long> {

    interface RateContractCandidateProjection {
        Long getGroupId();
        String getNationalMaterialCode();
        String getStandardizedDescription();
        String getCategory();
        Long getDistinctCpseCount();
        Long getTotalMemberCount();
        String getParticipatingCpses();
    }

    interface PriceVarianceProjection {
        Long getGroupId();
        String getNationalCode();
        String getDescription();
        String getCategory();
        java.math.BigDecimal getMinPriceInr();
        java.math.BigDecimal getMaxPriceInr();
    }

    interface CpsePriceProjection {
        Long getGroupId();
        String getCpseName();
        java.math.BigDecimal getMinPrice();
        java.math.BigDecimal getMaxPrice();
        java.math.BigDecimal getAveragePrice();
    }

    List<MaterialMapping> findByMaterial_MaterialId(Long materialId);

    @Query("SELECT mm FROM MaterialMapping mm WHERE mm.material.materialId = :materialId " +
            "AND mm.status IN ('PENDING', 'CONFIRMED')")
    Optional<MaterialMapping> findActiveByMaterialId(@Param("materialId") Long materialId);

    List<MaterialMapping> findByStatus(String status);

    Page<MaterialMapping> findByStatus(String status, Pageable pageable);

    @Query("SELECT mm FROM MaterialMapping mm WHERE mm.status = :status AND mm.group.category.categoryId IN :categoryIds")
    Page<MaterialMapping> findByStatusAndCategories(@Param("status") String status, @Param("categoryIds") Collection<Long> categoryIds, Pageable pageable);

    @Query("SELECT mm FROM MaterialMapping mm WHERE mm.status = :status AND mm.group.category.categoryId IN :categoryIds")
    List<MaterialMapping> findByStatusAndCategoriesList(@Param("status") String status, @Param("categoryIds") Collection<Long> categoryIds);

    List<MaterialMapping> findByGroup_GroupId(Long groupId);

    List<MaterialMapping> findByGroup_GroupIdAndStatus(Long groupId, String status);

    long countByStatus(String status);

    long countByGroup_GroupIdAndStatus(Long groupId, String status);

    @Query(value = "SELECT COALESCE(SUM(x.member_count - 1), 0) FROM (" +
            "SELECT COUNT(*) AS member_count FROM material_mapping mm " +
            "JOIN material_group g ON g.group_id = mm.group_id " +
            "WHERE g.status = 'ACTIVE' AND mm.status = 'CONFIRMED' " +
            "GROUP BY g.group_id HAVING COUNT(*) > 1) x", nativeQuery = true)
    long countConfirmedDuplicatesEliminated();

    @Query(value = "SELECT g.group_id AS \"groupId\", COALESCE(g.common_material_code, g.provisional_ref) AS \"nationalMaterialCode\", " +
            "g.standardized_description AS \"standardizedDescription\", COALESCE(mc.name, 'GENERAL') AS category, " +
            "COUNT(DISTINCT c.cpse_id) AS \"distinctCpseCount\", COUNT(*) AS \"totalMemberCount\", " +
            "string_agg(DISTINCT c.name, ', ' ORDER BY c.name) AS \"participatingCpses\" " +
            "FROM material_mapping mm JOIN material_group g ON g.group_id = mm.group_id " +
            "JOIN material m ON m.material_id = mm.material_id JOIN cpse c ON c.cpse_id = m.cpse_id " +
            "LEFT JOIN material_category mc ON mc.category_id = g.category_id " +
            "WHERE mm.status = 'CONFIRMED' GROUP BY g.group_id, g.common_material_code, g.provisional_ref, " +
            "g.standardized_description, mc.name HAVING COUNT(DISTINCT c.cpse_id) >= 2 " +
            "ORDER BY COUNT(DISTINCT c.cpse_id) DESC, g.group_id", nativeQuery = true)
    List<RateContractCandidateProjection> findRateContractCandidates();

    @Query(value = "SELECT g.group_id AS \"groupId\", COALESCE(g.common_material_code, g.provisional_ref) AS \"nationalCode\", " +
            "g.standardized_description AS description, COALESCE(mc.name, 'GENERAL') AS category, " +
            "MIN(m.nominal_price) AS \"minPriceInr\", MAX(m.nominal_price) AS \"maxPriceInr\" " +
            "FROM material_mapping mm JOIN material_group g ON g.group_id = mm.group_id " +
            "JOIN material m ON m.material_id = mm.material_id " +
            "LEFT JOIN material_category mc ON mc.category_id = g.category_id " +
            "WHERE mm.status = 'CONFIRMED' AND m.nominal_price IS NOT NULL " +
            "GROUP BY g.group_id, g.common_material_code, g.provisional_ref, g.standardized_description, mc.name " +
            "HAVING COUNT(DISTINCT m.cpse_id) >= 2 AND MAX(m.nominal_price) > MIN(m.nominal_price) " +
            "ORDER BY (MAX(m.nominal_price) - MIN(m.nominal_price)) DESC", nativeQuery = true)
    List<PriceVarianceProjection> findPriceVarianceGroups();

    @Query(value = "SELECT mm.group_id AS \"groupId\", c.name AS \"cpseName\", MIN(m.nominal_price) AS \"minPrice\", " +
            "MAX(m.nominal_price) AS \"maxPrice\", AVG(m.nominal_price) AS \"averagePrice\" " +
            "FROM material_mapping mm JOIN material m ON m.material_id = mm.material_id " +
            "JOIN cpse c ON c.cpse_id = m.cpse_id " +
            "WHERE mm.status = 'CONFIRMED' AND m.nominal_price IS NOT NULL " +
            "GROUP BY mm.group_id, c.cpse_id, c.name ORDER BY mm.group_id, c.name", nativeQuery = true)
    List<CpsePriceProjection> findCpsePriceSummaries();

    @Query("SELECT mm FROM MaterialMapping mm WHERE mm.status = 'PENDING' AND mm.confidenceTier = 'HIGH' AND mm.group.category.categoryId IN :categoryIds")
    List<MaterialMapping> findHighConfidencePendingForCategories(@Param("categoryIds") Collection<Long> categoryIds);

    @Query("SELECT mm FROM MaterialMapping mm WHERE mm.status = 'PENDING' AND mm.confidenceTier = 'HIGH'")
    List<MaterialMapping> findHighConfidencePendingAll();
}
