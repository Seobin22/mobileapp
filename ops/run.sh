#!/usr/bin/env bash
# test.seobin.page 를 seobin.page 인증서에 추가하고 nginx에 반영한다.
set -euo pipefail

certbot certonly --nginx --non-interactive --expand \
  --cert-name seobin.page -d seobin.page -d test.seobin.page

nginx -t
systemctl reload nginx

openssl x509 -in /etc/letsencrypt/live/seobin.page/cert.pem -noout -ext subjectAltName
