package com.nickneim.jeopardy.dto;

import com.nickneim.jeopardy.entity.ClueType;

public record GameClueResponse(
    Long id,
    String question,
    String answer,
    ClueType clueType,
    String mediaUrl,

    short rowNumber
) {}