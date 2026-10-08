package com.nickneim.jeopardy.game.controller;

import com.nickneim.jeopardy.game.dto.GameResponse;
import com.nickneim.jeopardy.game.dto.GameSummaryResponse;
import com.nickneim.jeopardy.game.dto.PageResponse;
import com.nickneim.jeopardy.game.dto.SeasonResponse;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.service.GameService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
        return GameResponse.from(gameService.getGame(id));
    }

    @GetMapping("/seasons")
    public List<SeasonResponse> getSeasons() {
        return gameService.getSeasons()
                .stream()
                .map(SeasonResponse::from)
                .toList();
    }

    @GetMapping
    public PageResponse<GameSummaryResponse> getGames(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String season
    ) {
        Page<Game> games = gameService.getGames(page, size, season);

        List<GameSummaryResponse> content = games.getContent()
                .stream()
                .map(GameSummaryResponse::from)
                .toList();

        return new PageResponse<>(
                content,
                games.getNumber(),
                games.getSize(),
                games.getTotalElements(),
                games.getTotalPages()
        );
    }
}