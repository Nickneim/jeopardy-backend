package com.nickneim.jeopardy.game.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

@Entity
@Table(
        name = "clue",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_clue_category_row",
                        columnNames = {"category_id", "row_number"}
                )
        }
)
public class Clue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "question", nullable = false)
    private String question;

    @Column(name = "answer", nullable = false)
    private String answer;

    @Column(name = "row_number", nullable = false)
    private short rowNumber;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "type", nullable = false)
    private ClueType clueType = ClueType.TEXT;

    @Column(name = "media_url")
    private String mediaUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    protected Clue() {
    }

    public Clue(
            String question,
            String answer,
            short rowNumber,
            ClueType clueType,
            String mediaUrl,
            Category category
    ) {
        this.question = question;
        this.answer = answer;
        this.rowNumber = rowNumber;
        this.clueType = clueType;
        this.mediaUrl = mediaUrl;
        this.category = category;
    }

    public Long getId() {
        return id;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public short getRowNumber() {
        return rowNumber;
    }

    public ClueType getClueType() {
        return clueType;
    }

    public String getMediaUrl() {
        return mediaUrl;
    }

    public Category getCategory() {
        return category;
    }

    public int getValue() {
        return switch (getRound()) {
            case JEOPARDY -> 200 * rowNumber;
            case DOUBLE_JEOPARDY -> 400 * rowNumber;
            case FINAL_JEOPARDY -> 2000;
        };
    }

    public boolean isFinalClue() {
        return getRound() == GameRound.FINAL_JEOPARDY;
    }

    private GameRound getRound() {
        return GameRound.fromColumn(category.getColumnNumber());
    }
}