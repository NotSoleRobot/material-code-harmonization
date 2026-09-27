package com.sih.materialmaster.exception;

import lombok.Getter;

/**
 * WP4 Task 8: Thrown when a reviewer attempts to confirm/reject a material
 * from their own CPSE, violating the 4-eyes Conflict of Interest policy.
 */
@Getter
public class ConflictOfInterestException extends RuntimeException {
    private final String reviewerCpse;
    private final String materialCpse;

    public ConflictOfInterestException(String message, String reviewerCpse, String materialCpse) {
        super(message);
        this.reviewerCpse = reviewerCpse;
        this.materialCpse = materialCpse;
    }
}
