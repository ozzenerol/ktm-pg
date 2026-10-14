#!/bin/sh
# Runs inside the container at first start (docker-entrypoint-initdb.d).
set -e
psql -v ON_ERROR_STOP=1 -U postgres <<SQL
SET password_encryption = 'md5';
CREATE USER ktm_md5 PASSWORD 'md5pw';
SET password_encryption = 'scram-sha-256';
CREATE USER ktm_scram PASSWORD 'scrampw';
CREATE USER ktm_clear PASSWORD 'clearpw';
CREATE USER ktm_trust;
CREATE DATABASE ktm_test;
SQL
# md5 in pg_hba lets each user log in with how its password is stored (md5 or scram)
sed -i '/^host all all all/d' "$PGDATA/pg_hba.conf"
cat >> "$PGDATA/pg_hba.conf" <<HBA
host all ktm_clear all password
host all ktm_trust all trust
host all all all md5
HBA
