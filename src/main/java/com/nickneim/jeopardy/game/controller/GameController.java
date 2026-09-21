package com.nickneim.jeopardy.game.controller;

import com.nickneim.jeopardy.game.dto.GameResponse;
import com.nickneim.jeopardy.game.dto.GameSummaryResponse;
import com.nickneim.jeopardy.game.dto.PageResponse;
import com.nickneim.jeopardy.game.service.GameService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games")
@Validated
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/{id}")
    public GameResponse getClue(@PathVariable Long id) {
        return gameService.getGame(id);
    }

    @GetMapping
    public PageResponse<GameSummaryResponse> getGames(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String season
    ) {
        return gameService.getGames(page, size, season);
    }
}