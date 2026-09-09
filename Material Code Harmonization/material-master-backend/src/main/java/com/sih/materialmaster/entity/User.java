package com.sih.materialmaster.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

// NOTE: "user" is a reserved word in PostgreSQL. The backticks below tell
// Hibernate to quote the identifier for whichever DB dialect is active,
// so it comes out as "user" in the generated SQL - matching the schema exactly.
@Entity
@Table(name = "`user`")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", nullable = false, unique = true, length = 150)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    // OPERATOR | REVIEWER | ADMIN
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    // Nullable: populated for OPERATOR (their CPSE), null for REVIEWER/ADMIN.
    // NOTE: the role/cpseId pairing rule itself is enforced by a DB CHECK
    // constraint (see schema.sql), not by JPA - annotations alone can't
    // express "this FK is required only when role = X." Spring Boot service
    // code should still validate the same rule before insert, for a clean
    // error message instead of a raw DB constraint violation reaching the user.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cpse_id")
    private Cpse cpse;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
