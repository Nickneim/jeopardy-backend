-- Turns square brackets in answers into parentheses, so the answer matcher
-- only has one kind to read. J-Archive uses brackets for the same things as
-- parentheses: optional parts ("[Albert] Einstein"), alternatives
-- ("Sandra Day-O'Connor [or Ruth Bader Ginsburg]"), pronunciations
-- ("minute [MIN-it]") and gestures ("[smiling and waving]").
--
-- Run it after an import and before answers.py apply, whose decisions are
-- keyed by the answer text with parentheses. A second run finds nothing.
--
-- Dry run (read-only, the default): lists the answers it would change.
--   scripts/data/psql.sh -f scripts/data/brackets-to-parentheses.sql
-- Change them:
--   WRITE=1 scripts/data/psql.sh -v commit=1 -f scripts/data/brackets-to-parentheses.sql

select answer as before, translate(answer, '[]', '()') as after, count(*) as clues
from clue
where answer ~ '[][]'
group by answer
order by answer;

\if :{?commit}
    begin;
    update clue set answer = translate(answer, '[]', '()') where answer ~ '[][]';
    commit;
    \echo 'Changed.'
\else
    \echo 'Dry run: nothing was changed. To change them, run with WRITE=1 and -v commit=1.'
\endif
