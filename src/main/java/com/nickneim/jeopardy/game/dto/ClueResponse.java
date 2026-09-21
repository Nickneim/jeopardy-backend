package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.entity.Clue;
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
) {

    public static ClueResponse from(Clue clue) {
        return new ClueResponse(
            clue.getId(),
            clue.getQuestion(),
            clue.getAnswer(),
            clue.getClueType(),
            clue.getMediaUrl(),

            clue.getValue(),
            clue.isFinalClue(),

            clue.getCategory().getId(),
            clue.getCategory().getName(),
            clue.getCategory().getComments()
        );
    }

}