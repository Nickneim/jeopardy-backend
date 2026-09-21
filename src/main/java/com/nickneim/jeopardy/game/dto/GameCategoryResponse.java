package com.nickneim.jeopardy.game.dto;

import java.util.List;

public record GameCategoryResponse(
        Long id,
        String name,
        String comments,
        short columnNumber,
        List<GameClueResponse> clues
) {}