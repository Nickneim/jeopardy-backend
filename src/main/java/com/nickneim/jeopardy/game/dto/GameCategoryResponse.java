package com.nickneim.jeopardy.game.dto;

import com.nickneim.jeopardy.game.entity.Category;
import com.nickneim.jeopardy.game.service.GameService;

import java.util.List;

public record GameCategoryResponse(
        Long id,
        String name,
        String comments,
        short columnNumber,
        List<GameClueResponse> clues
) {

    public static GameCategoryResponse from(Category category) {
        return new GameCategoryResponse(
                category.getId(),
                category.getName(),
                category.getComments(),
                category.getColumnNumber(),
                category.getClues().stream().map(GameClueResponse::from).toList()
        );
    }

}