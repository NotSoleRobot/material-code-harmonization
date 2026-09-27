package com.sih.materialmaster.repository;

import com.sih.materialmaster.entity.GroupRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GroupRelationRepository extends JpaRepository<GroupRelation, Long> {

    List<GroupRelation> findByGroupA_GroupIdOrGroupB_GroupId(Long groupAId, Long groupBId);

    @Query("SELECT gr FROM GroupRelation gr WHERE (gr.groupA.groupId = :g1 AND gr.groupB.groupId = :g2) OR (gr.groupA.groupId = :g2 AND gr.groupB.groupId = :g1)")
    Optional<GroupRelation> findRelationBetween(@Param("g1") Long g1, @Param("g2") Long g2);
}
