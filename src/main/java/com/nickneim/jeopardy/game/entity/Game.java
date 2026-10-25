package com.nickneim.jeopardy.game.entity;

import java.util.List;
import java.util.ArrayList;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "game")
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, unique = true)
    private String title;

    @Column(name = "game_date", nullable = false)
    private LocalDate gameDate;

    @Column(name = "season", nullable = false)
    private String season;

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getGameDate() {
        return gameDate;
    }

    public String getSeason() {
        return season;
    }

    protected Game() {}

    public Game(String title, LocalDate gameDate, String season) {
        this.title = title;
        this.gameDate = gameDate;
        this.season = season;
    }

    @OneToMany(
            mappedBy = "game",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("columnNumber ASC")
    private List<Category> categories = new ArrayList<>();

    @OneToMany(
            mappedBy = "game",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("id.clueId ASC")
    private List<GameDailyDouble> dailyDoubles = new ArrayList<>();

    public List<Category> getCategories() {
        return categories;
    }

    public List<GameDailyDouble> getDailyDoubles() {
        return dailyDoubles;
    }

    public boolean isDailyDouble(Long clueId) {
        return dailyDoubles.stream()
                .anyMatch(dailyDouble -> dailyDouble.getClue().getId().equals(clueId));
    }
}