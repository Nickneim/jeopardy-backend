package com.nickneim.jeopardy.game.service;


import com.nickneim.jeopardy.game.dto.GameSummaryResponse;
import com.nickneim.jeopardy.game.exception.GameNotFoundException;
import com.nickneim.jeopardy.game.dto.GameResponse;
import com.nickneim.jeopardy.game.entity.Game;
import com.nickneim.jeopardy.game.repository.GameRepository;
import com.nickneim.jeopardy.game.repository.SeasonGameCount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GameService {

    // Numbered seasons first, highest number first (numerically, so "9" comes
    // after "40"). Then special seasons ("jm", "pcj"...), most recent game first.
    private static final Comparator<SeasonGameCount> NEWEST_SEASON_FIRST =
            Comparator.comparing(
                            (SeasonGameCount season) -> Seasons.number(season.season()),
                            Comparator.nullsLast(Comparator.<BigInteger>reverseOrder())
                    )
                    .thenComparing(SeasonGameCount::latestGameDate, Comparator.reverseOrder())
                    .thenComparing(SeasonGameCount::season);

    private final GameRepository gameRepository;

    public GameService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }


    /**
     * The game's categories, clues and daily doubles load lazily, so this
     * only runs inside the caller's transaction: without one it fails at
     * once instead of on the first lazy read.
     */
    @Transactional(propagation = Propagation.MANDATORY)
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

    /**
     * A page of GET /api/games. The page and its clue counts are read in one
     * transaction, so they come from the same state of the database.
     */
    @Transactional(readOnly = true)
    public Page<GameSummaryResponse> getGameSummaries(int page, int size, GameFilter filter, GameSort sort) {
        Page<Game> games = getGames(page, size, filter, sort);
        Map<Long, Long> clueCounts = countClues(games.getContent());

        return games.map(game -> GameSummaryResponse.from(game, clueCounts.get(game.getId())));
    }

    // Package-private: only getGameSummaries' transaction keeps the page and
    // its counts consistent, so nothing else should call these two.
    Page<Game> getGames(int page, int size, GameFilter filter, GameSort sort) {
        // Unsorted: the order is an expression (a clue count, the season's
        // number), so the specification sets it.
        Pageable pageable = PageRequest.of(page, size);

        Specification<Game> matching = GameSpecifications.matching(filter);

        // JPA can't skip more rows than an int counts, and no page that far
        // has games: it's past the end, like any other page there.
        if (pageable.getOffset() > Integer.MAX_VALUE) {
            return new PageImpl<>(List.of(), pageable, gameRepository.count(matching));
        }

        return gameRepository.findAll(matching.and(GameSpecifications.sortedBy(sort)), pageable);
    }

    /**
     * How many clues each game has, by game id, with one grouped query.
     * Games without clues map to 0.
     */
    Map<Long, Long> countClues(Collection<Game> games) {
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


}
