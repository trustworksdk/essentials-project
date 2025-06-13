/* When using test containers with reuse then use this init script that clears the database */
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;