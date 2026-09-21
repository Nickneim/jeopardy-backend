package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.entity.Clue;
import com.nickneim.jeopardy.game.entity.ClueType;

public record GameClueResponse(
    Long id,
    String question,
    String answer,
    ClueType clueType,
    String mediaUrl,

    short rowNumber
) {

    public static GameClueResponse from(Clue clue) {
        return new GameClueResponse(
                clue.getId(),
                clue.getQuestion(),
                clue.getAnswer(),
                clue.getClueType(),
                clue.getMediaUrl(),
                clue.getRowNumber()
        );
    }


}