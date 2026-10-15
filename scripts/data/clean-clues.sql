-- Deletes clues that J-Archive has no content for: its pages show "=" (or a
-- row of dashes, or a lone "(") in place of the clue or the response. Only
-- clues are deleted; categories and games stay, even if they end up empty.
-- Daily Double rows for these clues go with them (ON DELETE CASCADE).
--
-- Clues are matched by content, never by id, so this works after a re-import
-- and finds nothing on a second run.
--
-- Dry run (read-only, the default): lists what would be deleted.
--   scripts/data/psql.sh -f scripts/data/clean-clues.sql
-- Delete:
--   WRITE=1 scripts/data/psql.sh -v commit=1 -f scripts/data/clean-clues.sql

\set broken '(clue.answer ~ ''^[[:space:]=-]*$'' or clue.question ~ ''^[[:space:]=(-]*$'')'

\echo 'Broken clues by game:'
select game.title, game.season, count(*) as broken_clues,
       (select count(*) from category c join clue cl on cl.category_id = c.id where c.game_id = game.id) as all_clues
from clue
join category on category.id = clue.category_id
join game on game.id = category.game_id
where :broken
group by game.id, game.title, game.season
order by game.id;

select count(*) as broken_clues,
       count(*) filter (where exists (select 1 from game_daily_double dd where dd.clue_id = clue.id)) as daily_doubles
from clue
where :broken;

\if :{?commit}
    begin;
    delete from clue where :broken;
    commit;
    \echo 'Deleted.'
\else
    \echo 'Dry run: nothing was changed. To delete, run with WRITE=1 and -v commit=1.'
\endif
