package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.MaterialMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MaterialMappingRepository extends JpaRepository<MaterialMapping, Long> {

    List<MaterialMapping> findByMaterial_MaterialId(Long materialId);

    @Query("SELECT mm FROM MaterialMapping mm WHERE mm.material.materialId = :materialId " +
            "AND mm.status IN ('PENDING', 'CONFIRMED')")
    Optional<MaterialMapping> findActiveByMaterialId(@Param("materialId") Long materialId);

    List<MaterialMapping> findByStatus(String status);

    @EntityGraph(attributePaths = {"material", "material.cpse", "group", "group.category", "reviewedBy"})
    Page<MaterialMapping> findByStatus(String status, Pageable pageable);

    @Query("SELECT mm FROM MaterialMapping mm WHERE mm.status = :status AND mm.group.category.categoryId IN :categoryIds")
    @EntityGraph(attributePaths = {"material", "material.cpse", "group", "group.category", "reviewedBy"})
    Page<MaterialMapping> findByStatusAndCategories(@Param("status") String status, @Param("categoryIds") Collection<Long> categoryIds, Pageable pageable);

    @Query("SELECT mm FROM MaterialMapping mm WHERE mm.status = :status AND mm.group.category.categoryId IN :categoryIds")
    List<MaterialMapping> findByStatusAndCategoriesList(@Param("status") String status, @Param("categoryIds") Collection<Long> categoryIds);

    List<MaterialMapping> findByGroup_GroupId(Long groupId);

    List<MaterialMapping> findByGroup_GroupIdAndStatus(Long groupId, String status);

    @Query("SELECT mm FROM MaterialMapping mm JOIN FETCH mm.material m JOIN FETCH m.cpse " +
            "LEFT JOIN FETCH mm.reviewedBy WHERE mm.group.groupId IN :groupIds " +
            "AND mm.status IN ('PENDING', 'CONFIRMED')")
    List<MaterialMapping> findCatalogMappingsForGroups(@Param("groupIds") Collection<Long> groupIds);

    @Query("SELECT mm FROM MaterialMapping mm JOIN FETCH mm.material m JOIN FETCH m.cpse " +
            "LEFT JOIN FETCH mm.reviewedBy WHERE mm.group.groupId IN :groupIds AND mm.status = 'CONFIRMED'")
    List<MaterialMapping> findConfirmedForGroups(@Param("groupIds") Collection<Long> groupIds);

    long countByStatus(String status);

    long countByGroup_GroupIdAndStatus(Long groupId, String status);

    @Query(value = "SELECT COALESCE(SUM(x.member_count - 1), 0) FROM (" +
            "SELECT COUNT(*) AS member_count FROM material_mapping mm " +
            "JOIN material_group g ON g.group_id = mm.group_id " +
            "WHERE g.status IN ('PROPOSED', 'ACTIVE') AND mm.status = 'CONFIRMED' " +
            "GROUP BY g.group_id HAVING COUNT(*) > 1) x", nativeQuery = true)
    long countConfirmedDuplicatesEliminated();

    @Query("SELECT mm FROM MaterialMapping mm WHERE mm.status = 'PENDING' AND mm.confidenceTier = 'HIGH' AND mm.group.category.categoryId IN :categoryIds")
    List<MaterialMapping> findHighConfidencePendingForCategories(@Param("categoryIds") Collection<Long> categoryIds);

    @Query("SELECT mm FROM MaterialMapping mm WHERE mm.status = 'PENDING' AND mm.confidenceTier = 'HIGH'")
    List<MaterialMapping> findHighConfidencePendingAll();
}
