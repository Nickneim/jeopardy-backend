package com.nickneim.jeopardy.dto;

import com.nickneim.jeopardy.entity.ClueType;

public record ClueResponse(
        Long id,
        String question,
        String answer,
        ClueType clueType,
        String mediaUrl,

        Integer value,
        boolean isFinalClue,

        Long categoryId,
        String categoryName,
        String categoryComments
) {}