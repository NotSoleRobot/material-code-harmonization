package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.ReviewerAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ReviewerAssignmentRepository extends JpaRepository<ReviewerAssignment, Long> {

    List<ReviewerAssignment> findByUser_UserId(Long userId);

    @Query("SELECT ra.category.categoryId FROM ReviewerAssignment ra WHERE ra.user.userId = :userId")
    List<Long> findCategoryIdsByUserId(@Param("userId") Long userId);

    void deleteByUser_UserId(Long userId);
}
