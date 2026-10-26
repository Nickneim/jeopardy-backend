package com.nickneim.jeopardy.game.controller;

import com.nickneim.jeopardy.game.dto.GameResponse;
import com.nickneim.jeopardy.game.dto.GameSummaryResponse;
import com.nickneim.jeopardy.dto.PageResponse;
import com.nickneim.jeopardy.game.dto.SeasonResponse;
import com.nickneim.jeopardy.game.service.GameFilter;
import com.nickneim.jeopardy.game.service.GameService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
    public GameResponse getGame(@PathVariable Long id) {
        return gameService.getGameResponse(id);
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
            @RequestParam(required = false) String season,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Boolean playable
    ) {
        return PageResponse.from(gameService.getGameSummaries(
                page,
                size,
                new GameFilter(season, title, from, to, playable)
        ));
    }
}