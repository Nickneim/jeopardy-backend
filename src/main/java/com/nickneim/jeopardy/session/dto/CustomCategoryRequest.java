package com.nickneim.jeopardy.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.Range;

import java.util.List;

/**
 * A category of a custom show's round. {@code name} is null or blank for a
 * category the host never named. {@code sourceCategoryId} is the stored
 * category it was imported from, for information only: nothing reads it.
 * A row without a clue is left out of {@code clues}. Columns are unique in
 * a round and rows in a category, which {@code CustomGameBoards} checks.
 */
public record CustomCategoryRequest(
        @NotNull(message = "must not be null")
        @Range(min = 1, max = CustomGameRequest.MAX_CATEGORIES, message = "must be between 1 and " + CustomGameRequest.MAX_CATEGORIES)
        Integer column,
        String name,
        String comments,
        Long sourceCategoryId,
        @NotNull(message = "must not be null")
        @Size(max = CustomClueRequest.MAX_ROW, message = "must have at most " + CustomClueRequest.MAX_ROW + " clues")
        List<@NotNull(message = "must not be null") @Valid CustomClueRequest> clues
) {
}
