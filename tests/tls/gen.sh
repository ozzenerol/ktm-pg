#!/bin/sh
# Test certificates for ktm-pg's TLS tests (never use them for anything
# else: the keys are public). A CA, a server certificate it signs for
# localhost / 127.0.0.1 / ::1, a client certificate for the user ktm_cert,
# and an unrelated CA. Valid for 100 years. Run once; the files are kept.
set -e
cd "$(dirname "$0")"
d=36500
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.pem -days $d -subj "/CN=ktm-pg test CA" 2>/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -keyout other-ca.key -out other-ca.pem -days $d -subj "/CN=ktm-pg other CA" 2>/dev/null
openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=localhost" 2>/dev/null
printf 'subjectAltName=DNS:localhost,IP:127.0.0.1,IP:::1\n' > server.ext
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -days $d -extfile server.ext 2>/dev/null
openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr -subj "/CN=ktm_cert" 2>/dev/null
openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem -days $d 2>/dev/null
rm -f server.csr client.csr server.ext ca.srl other-ca.key
chmod 644 *.key    # test keys: the Postgres container (another uid) reads server.key
