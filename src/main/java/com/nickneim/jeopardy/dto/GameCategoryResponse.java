package com.nickneim.jeopardy.dto;

import java.util.List;

public record GameCategoryResponse(
        Long id,
        String name,
        String comments,
        short columnNumber,
        List<GameClueResponse> clues
) {}