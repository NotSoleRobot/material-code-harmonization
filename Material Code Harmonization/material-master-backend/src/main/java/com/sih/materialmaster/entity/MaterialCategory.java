package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "material_category")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MaterialCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "level", nullable = false)
    private Integer level; // 1: Segment, 2: Family, 3: Class

    @Column(name = "code_segment", length = 10)
    private String codeSegment; // e.g. "40"

    @Column(name = "code_family", length = 10)
    private String codeFamily; // e.g. "14"

    @Column(name = "code_class", length = 10)
    private String codeClass; // e.g. "07"

    // Self-referencing FK: parent category. Null for top-level categories.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private MaterialCategory parent;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_custom", nullable = false)
    private Boolean custom = false;
}
