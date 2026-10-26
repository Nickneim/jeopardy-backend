package com.nickneim.jeopardy.session.dto;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.CodePointLength;

// Explicit messages aren't localized, so error responses read the same
// whatever the client's Accept-Language. The name is in every broadcast, so
// its length is capped, in code points (what JavaScript's [...name].length
// counts), so an emoji counts as 1, not as its 2 UTF-16 units.
public record JoinSessionRequest(
        @NotBlank(message = "must not be blank")
        @CodePointLength(max = MAX_NAME_LENGTH, message = "must be at most " + MAX_NAME_LENGTH + " characters")
        String playerName
) {
    public static final int MAX_NAME_LENGTH = 30;

    // Validation runs on the cleaned name (PlayerNames), so a name of only
    // spaces is blank, and "Bob " can't pass for "Bob". @NotBlank alone only
    // ignores ASCII spaces and controls.
    public JoinSessionRequest {
        playerName = playerName == null ? null : PlayerNames.clean(playerName);
    }
}
