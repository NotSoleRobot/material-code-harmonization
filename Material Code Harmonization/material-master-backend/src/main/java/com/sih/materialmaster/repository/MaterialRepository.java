package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.Material;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    interface CategoryCount {
        String getCategoryName();
        Long getMaterialCount();
    }

    interface CpseMappingCount {
        String getCpseName();
        Long getMaterialCount();
        Long getMappedCount();
    }

    List<Material> findByCpse_CpseId(Long cpseId);

    Page<Material> findByCpse_CpseId(Long cpseId, Pageable pageable);

    Optional<Material> findByCpse_CpseIdAndCpseMaterialCode(Long cpseId, String cpseMaterialCode);

    @Query("SELECT m FROM Material m WHERE m.materialId != :excludeId AND m.category.categoryId = :categoryId ORDER BY m.materialId ASC")
    List<Material> findCandidatesByCategory(@Param("categoryId") Long categoryId, @Param("excludeId") Long excludeId, Pageable pageable);

    @Query("SELECT m FROM Material m WHERE m.materialId != :excludeId ORDER BY m.materialId ASC")
    List<Material> findCandidatesAll(@Param("excludeId") Long excludeId, Pageable pageable);

    @Query(
        "SELECT m FROM Material m WHERE NOT EXISTS " +
        "(SELECT mm.mappingId FROM MaterialMapping mm WHERE mm.material = m AND mm.status != 'REJECTED')"
    )
    List<Material> findUnmatched();

    @Query(
        "SELECT COUNT(m) FROM Material m WHERE NOT EXISTS " +
        "(SELECT mm.mappingId FROM MaterialMapping mm WHERE mm.material = m AND mm.status != 'REJECTED')"
    )
    long countUnmatched();

    long countByCpse_CpseId(Long cpseId);

    @Query("SELECT COALESCE(c.name, 'GENERAL') AS categoryName, COUNT(m) AS materialCount " +
           "FROM Material m LEFT JOIN m.category c GROUP BY c.name ORDER BY c.name")
    List<CategoryCount> countByCategory();

    @Query("SELECT c.name AS cpseName, COUNT(DISTINCT m.materialId) AS materialCount, " +
           "COUNT(DISTINCT CASE WHEN mm.status IN ('PENDING','CONFIRMED') THEN m.materialId ELSE NULL END) AS mappedCount " +
           "FROM Material m JOIN m.cpse c LEFT JOIN MaterialMapping mm ON mm.material = m " +
           "GROUP BY c.cpseId, c.name ORDER BY c.name")
    List<CpseMappingCount> countMaterialsAndMappingsByCpse();

    @Query("SELECT m FROM Material m WHERE LOWER(m.description) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(m.cpseMaterialCode) LIKE LOWER(CONCAT('%', :query, '%'))")
    Page<Material> searchMaterials(@Param("query") String query, Pageable pageable);

    @Query("SELECT m FROM Material m WHERE m.cpse.cpseId = :cpseId AND (LOWER(m.description) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(m.cpseMaterialCode) LIKE LOWER(CONCAT('%', :query, '%')))")
    Page<Material> searchMaterialsByCpse(@Param("cpseId") Long cpseId, @Param("query") String query, Pageable pageable);
}
