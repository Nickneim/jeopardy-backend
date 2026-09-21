package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.entity.ClueType;

public record GameClueResponse(
    Long id,
    String question,
    String answer,
    ClueType clueType,
    String mediaUrl,

    short rowNumber
) {}