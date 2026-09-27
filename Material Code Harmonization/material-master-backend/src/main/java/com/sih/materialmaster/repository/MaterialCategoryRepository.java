package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.MaterialCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaterialCategoryRepository extends JpaRepository<MaterialCategory, Long> {

    Optional<MaterialCategory> findByNameIgnoreCase(String name);

    List<MaterialCategory> findByLevel(Integer level);

    List<MaterialCategory> findByParent_CategoryId(Long parentId);
}
