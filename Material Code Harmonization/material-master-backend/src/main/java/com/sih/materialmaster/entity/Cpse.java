package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "cpse")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Cpse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cpse_id")
    private Long cpseId;

    @Column(name = "name", nullable = false, unique = true, length = 150)
    private String name;

    @Column(name = "sector", length = 100)
    private String sector;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
