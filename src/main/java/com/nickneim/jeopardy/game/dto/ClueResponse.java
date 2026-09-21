package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.entity.ClueType;

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