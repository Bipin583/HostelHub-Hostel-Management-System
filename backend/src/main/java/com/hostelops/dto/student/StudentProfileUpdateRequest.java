package com.hostelops.dto.student;

import com.hostelops.validation.PhoneNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The subset of a student's record they may change themselves.
 *
 * <p>Deliberately narrow. Roll number, gender, year and allocation status are all
 * absent, because a request body that carries them is a request body that can
 * change them -- and a student editing their own year of study would silently move
 * themselves into another cohort's rooms. This is the mass-assignment hole that
 * closing means not opening: the DTO cannot express the change, so no handler has
 * to remember to reject it.
 */
public record StudentProfileUpdateRequest(
        @NotBlank(message = "A contact number is required")
        @PhoneNumber
        String mobileNo,

        @PhoneNumber
        String parentMobileNo,

        @Size(max = 100, message = "Branch name is too long")
        String branch) {
}
