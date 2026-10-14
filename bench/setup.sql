DROP TABLE IF EXISTS bench_users, bench_ins;
CREATE TABLE bench_users (id int8 PRIMARY KEY, name text NOT NULL, email text NOT NULL);
INSERT INTO bench_users SELECT i, 'user ' || i, 'user' || i || '@example.com' FROM generate_series(1, 10000) i;
CREATE TABLE bench_ins (id bigserial PRIMARY KEY, name text, n int8);
VACUUM ANALYZE bench_users;
