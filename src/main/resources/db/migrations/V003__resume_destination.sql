-- V003: True Resume (feature 007)
-- Records where each canonical file was actually written, so a re-run or a resumed job can tell
-- "I already transferred this" from "this is a new file that happens to collide by name".
--
-- Without this the destination is only recoverable by recomputation, which breaks as soon as a
-- collision suffix has been applied -- and recomputing was what made a resumed job re-copy its own
-- output as IMG001(1).jpg.
--
-- NOTE: keep every statement top-level. Database.splitStatements strips line comments and then
-- splits on the statement terminator, so avoid that character inside string literals.

ALTER TABLE HASH_CANONICAL ADD COLUMN DESTINATION_PATH TEXT;

ALTER TABLE HASH_CANONICAL ADD COLUMN DESTINATION_SIZE INTEGER;

PRAGMA user_version = 3;
