package com.nickneim.jeopardy.game.service;


import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.dto.GameResponse;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.repository.GameRepository;
import com.nickneim.jeopardy.game.repository.SeasonGameCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class GameService {

    private static final Pattern NUMBERED_SEASON = Pattern.compile("\\d+");

    // Numbered seasons first, highest number first (numerically, so "9" comes
    // after "40"). Then special seasons ("jm", "pcj"...), most recent game first.
    private static final Comparator<SeasonGameCount> NEWEST_SEASON_FIRST =
            Comparator.comparing((SeasonGameCount season) -> !isNumbered(season))
                    .thenComparing(GameService::seasonNumber, Comparator.reverseOrder())
                    .thenComparing(SeasonGameCount::latestGameDate, Comparator.reverseOrder())
                    .thenComparing(SeasonGameCount::season);

    private final GameRepository gameRepository;

    public GameService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }


    public Game getGame(Long id) {
        return gameRepository.findById(id)
                .orElseThrow(() -> new GameNotFoundException(id));
    }

    /**
     * The whole game, for GET /api/games/{id}. The response reads the game's
     * lazy collections, so it's built inside this transaction.
     */
    @Transactional(readOnly = true)
    public GameResponse getGameResponse(Long id) {
        return GameResponse.from(getGame(id));
    }

    public Page<Game> getGames(int page, int size, GameFilter filter) {
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("gameDate"),
                        Sort.Order.desc("id")
                )
        );

        return gameRepository.findAll(GameSpecifications.matching(filter), pageable);
    }

    /**
     * How many clues each game has, by game id, with one grouped query.
     * Games without clues map to 0.
     */
    public Map<Long, Long> countClues(Collection<Game> games) {
        if (games.isEmpty()) {
            return Map.of();
        }

        Map<Long, Long> counts = new HashMap<>();
        games.forEach(game -> counts.put(game.getId(), 0L));

        gameRepository.countCluesByGameIds(counts.keySet())
                .forEach(count -> counts.put(count.gameId(), count.clueCount()));

        return counts;
    }

    public List<SeasonGameCount> getSeasons() {
        return gameRepository.findSeasonGameCounts()
                .stream()
                .sorted(NEWEST_SEASON_FIRST)
                .toList();
    }

    private static boolean isNumbered(SeasonGameCount season) {
        return NUMBERED_SEASON.matcher(season.season()).matches();
    }

    private static BigInteger seasonNumber(SeasonGameCount season) {
        return isNumbered(season) ? new BigInteger(season.season()) : BigInteger.ZERO;
    }


}
