CREATE TYPE clue_type AS ENUM (
    'TEXT',
    'IMAGE',
    'SOUND',
    'VIDEO'
);

CREATE TABLE game (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    game_date DATE NOT NULL,
    title VARCHAR(255) NOT NULL,
    season VARCHAR(50) NOT NULL,

    CONSTRAINT uq_game_title UNIQUE (title)
);

CREATE TABLE category (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    column_number SMALLINT NOT NULL,
    comments TEXT,
    game_id BIGINT NOT NULL,

    CONSTRAINT fk_category_game
        FOREIGN KEY (game_id)
        REFERENCES game(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_category_game_column
        UNIQUE (game_id, column_number)
);

CREATE TABLE clue (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    row_number SMALLINT NOT NULL,
    type clue_type NOT NULL DEFAULT 'TEXT',
    media_url TEXT,
    category_id BIGINT NOT NULL,

    CONSTRAINT fk_clue_category
        FOREIGN KEY (category_id)
        REFERENCES category(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_clue_category_row
        UNIQUE (category_id, row_number),

    CONSTRAINT check_media_url
        CHECK (
            (type = 'TEXT' AND media_url IS NULL)
            OR
            (type <> 'TEXT' AND media_url IS NOT NULL)
        )
);

CREATE TABLE game_daily_double (
    game_id BIGINT NOT NULL,
    clue_id BIGINT NOT NULL,

    PRIMARY KEY (game_id, clue_id),

    CONSTRAINT fk_daily_double_game
        FOREIGN KEY (game_id)
        REFERENCES game(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_daily_double_clue
        FOREIGN KEY (clue_id)
        REFERENCES clue(id)
        ON DELETE CASCADE
);