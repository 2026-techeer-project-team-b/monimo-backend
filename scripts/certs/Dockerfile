# 인증서 일회성 컨테이너 (#159). compose 의 otlp-certs 서비스가 이 이미지로 scripts/certs/make-certs.sh 를 돌린다.
# clickhouse-migrate(db/clickhouse/Dockerfile)와 같은 성격 : 켤 때 한 번 돌고 끝난다. 없으면 만들고 있으면 아무것도 안 한다
FROM alpine:3.21
RUN apk add --no-cache openssl bash
COPY make-certs.sh /make-certs.sh
ENTRYPOINT ["bash", "/make-certs.sh", "/certs"]
