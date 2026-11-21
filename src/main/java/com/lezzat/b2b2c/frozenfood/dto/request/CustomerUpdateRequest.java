package com.lezzat.b2b2c.frozenfood.dto.request;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CustomerUpdateRequest {

    public static final String INDONESIAN_PHONE_REGEX = "^08[1-9][0-9]{7,10}$";
    public static final String INVALID_PHONE_MESSAGE =
            "Format nomor telepon tidak valid. Gunakan awalan 08 dengan panjang 10-13 digit.";

    private String nama;
    private String email;

    @Pattern(regexp = INDONESIAN_PHONE_REGEX, message = INVALID_PHONE_MESSAGE)
    private String noTelp;

    private String alamat;
    private String passwordBaru;

    public void setNoTelp(String noTelp) {
        this.noTelp = normalizePhone(noTelp);
    }

    public static String normalizePhone(String rawPhone) {
        if (rawPhone == null) {
            return null;
        }
        String normalized = rawPhone.trim();
        if (normalized.startsWith("+628")) {
            return "08" + normalized.substring(4);
        }
        if (normalized.startsWith("628")) {
            return "08" + normalized.substring(3);
        }
        return normalized;
    }
}