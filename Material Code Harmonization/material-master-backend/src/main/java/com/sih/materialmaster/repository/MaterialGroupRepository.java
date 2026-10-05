package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.MaterialGroup;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MaterialGroupRepository extends JpaRepository<MaterialGroup, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM MaterialGroup g WHERE g.groupId = :id")
    Optional<MaterialGroup> findLockedById(@Param("id") Long id);

    Optional<MaterialGroup> findByCommonMaterialCode(String commonMaterialCode);

    Optional<MaterialGroup> findByAttributeSignature(String attributeSignature);

    Optional<MaterialGroup> findByProvisionalRef(String provisionalRef);

    List<MaterialGroup> findByStatus(String status);

    Page<MaterialGroup> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);

    @Query("SELECT g FROM MaterialGroup g " +
           "WHERE (:status IS NULL OR g.status = :status) AND " +
           "(:category IS NULL OR LOWER(g.category.name) = LOWER(:category)) AND " +
           "(:query IS NULL OR LOWER(g.commonMaterialCode) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(g.standardizedDescription) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(g.provisionalRef) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR EXISTS (SELECT 1 FROM MaterialMapping mm JOIN mm.material m WHERE mm.group = g AND " +
           "(LOWER(m.cpseMaterialCode) LIKE LOWER(CONCAT('%', :query, '%')) " +
           "OR LOWER(m.description) LIKE LOWER(CONCAT('%', :query, '%')))))")
    Page<MaterialGroup> searchGroups(@Param("query") String query, @Param("status") String status,
                                     @Param("category") String category, Pageable pageable);

    @Query("SELECT DISTINCT g.category.name FROM MaterialGroup g WHERE g.category IS NOT NULL AND g.status = 'ACTIVE' ORDER BY g.category.name")
    List<String> findCatalogCategoryNames();

}
