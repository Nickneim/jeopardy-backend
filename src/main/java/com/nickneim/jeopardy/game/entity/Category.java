package com.nickneim.jeopardy.game.entity;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "category",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_category_game_column",
                        columnNames = {"game_id", "column_number"}
                )
        }
)
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "column_number", nullable = false)
    private short columnNumber;

    @Column(name = "comments")
    private String comments;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @OneToMany(
            mappedBy = "category",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @OrderBy("rowNumber ASC")
    private List<Clue> clues = new ArrayList<>();

    protected Category() {
    }

    public Category(String name, short columnNumber, String comments, Game game) {
        this.name = name;
        this.columnNumber = columnNumber;
        this.comments = comments;
        this.game = game;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public short getColumnNumber() {
        return columnNumber;
    }

    public String getComments() {
        return comments;
    }

    public Game getGame() {
        return game;
    }

    public List<Clue> getClues() {
        return clues;
    }

    public boolean isJeopardyRound() {
        return GameRound.fromColumn(getColumnNumber()) == GameRound.JEOPARDY;
    }

    public boolean isDoubleJeopardyRound() {
        return GameRound.fromColumn(getColumnNumber()) == GameRound.DOUBLE_JEOPARDY;
    }
    public boolean isFinalJeopardy() {
        return GameRound.fromColumn(getColumnNumber()) == GameRound.FINAL_JEOPARDY;
    }
}