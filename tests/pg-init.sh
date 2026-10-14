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
CREATE USER ktm_ssl PASSWORD 'sslpw';
CREATE USER ktm_cert;
CREATE DATABASE ktm_test;
SQL
# TLS with the test CA (tests/tls): the key must belong to postgres, 0600
cp /tls/server.pem /tls/server.key /tls/ca.pem "$PGDATA/"
chmod 600 "$PGDATA/server.key"
cat >> "$PGDATA/postgresql.conf" <<CONF
ssl = on
ssl_cert_file = 'server.pem'
ssl_key_file = 'server.key'
ssl_ca_file = 'ca.pem'
CONF
# md5 in pg_hba lets each user log in with how its password is stored (md5 or scram)
# only our rules for TCP: the image trusts 127.0.0.1, which is every
# client with --network host
sed -i '/^host/d' "$PGDATA/pg_hba.conf"
cat >> "$PGDATA/pg_hba.conf" <<HBA
hostssl all ktm_ssl all scram-sha-256
hostnossl all ktm_ssl all reject
hostssl all ktm_cert all cert
hostnossl all ktm_cert all reject
host all ktm_clear all password
host all ktm_trust all trust
host all all all md5
HBA
