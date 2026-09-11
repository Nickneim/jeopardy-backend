package com.nickneim.jeopardy.dto;

import com.nickneim.jeopardy.entity.ClueType;

public record ClueResponse(
        Long id,
        String question,
        String answer,
        short rowNumber,
        ClueType clueType,
        String mediaUrl,
        Long categoryId
) {}