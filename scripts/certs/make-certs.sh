#!/usr/bin/env bash
# 에이전트 mTLS 인증서 묶음을 만든다 (#159 · ADR #64). 로컬 compose 전용이다.
#
# 없으면 만들고 있으면 아무것도 안 한다 (멱등). 여러 번 돌려도 안전하다 : postgres-migrate · clickhouse-migrate 와 같은 성격.
# compose 의 otlp-certs 일회성 컨테이너가 이 스크립트를 돌리고, 수집기 시험(TestCerts)도 임시 폴더에 같은 스크립트를 돌린다.
# 그래서 시험이 쓰는 인증서와 compose 가 쓰는 인증서가 같은 코드로 만들어진다.
#
# 만드는 것 (research 7절 묶음 1 · 3 · 4) :
#   ca.crt · ca.key                    기관 1벌. 유효기간 10년. 도장을 찍는 열쇠라 컨테이너 밖으로 나갈 일이 없다
#   server.crt · server.key            수집기 1벌. 유효기간 5년. 이름은 collector · localhost (컨테이너끼리 · 내 컴퓨터)
#   clients/<이름>.crt · .key          손님. 쇼핑몰 네 서비스 + 점검 스크립트(check) 한 벌씩. 유효기간 5년
#   clients/expired.crt · .key         이미 만료된 손님 한 벌. 만료 경로를 일부러 밟는 시험 재료다 (7절 묶음 3)
#   env/<이름>.env                     쇼핑몰 손님마다 OTel 에이전트 환경변수 넷(https 주소 · 기관 인증서 · 신분증 · 열쇠 경로).
#                                      쇼핑몰 compose 가 env_file 로 읽는다. 환경변수를 빈 값으로 넣으면 에이전트가
#                                      "Invalid OTLP certificate/key path" 로 멈추므로(실측) 켤 때만 파일째 넣는 방식이다
#
# 열쇠는 전부 PKCS8 PEM(-----BEGIN PRIVATE KEY-----)이다. OTel Java Agent 가 그 형식만 읽는다 (공식 문서).
# openssl genpkey 가 그 형식으로 낸다. genrsa 는 OpenSSL 1.1 에서 PKCS1 로 내므로 쓰지 않는다.
#
# 사용 : make-certs.sh <만들 폴더>
#   CLIENTS 환경변수로 손님 이름 목록을 바꿀 수 있다 (공백 구분). 기본은 아래.
set -euo pipefail

OUT=${1:?사용: make-certs.sh <만들 폴더>}
CLIENTS=${CLIENTS:-shop-gateway shop-order shop-payment shop-inventory check}
CA_DAYS=3650      # 기관 10년
LEAF_DAYS=1825    # 신분증 5년 ("길게 + 재시작", 7절 묶음 3)
# 수집기 신분증에 적는 이름. 손님은 이 이름으로 접속해야 서버 쪽 검사를 통과한다 (컨테이너끼리 collector · 내 컴퓨터 localhost)
SERVER_SANS=${SERVER_SANS:-DNS:collector,DNS:localhost,IP:127.0.0.1,IP:::1}

mkdir -p "$OUT/clients"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# 열쇠 하나. 2048비트 RSA. 어디서나 읽히는 쪽을 골랐다 (EC 가 빠르지만 읽는 쪽 지원을 하나 더 확인해야 한다)
make_key() {
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$1" 2>/dev/null
  # 컨테이너가 root 로 만들고 수집기 컨테이너는 app 사용자로 읽는다. 로컬 전용 폴더라 읽기를 연다.
  # 운영에서는 이 스크립트를 쓰지 않는다 (비밀값은 Secrets Manager, ADR #62 ⑦)
  chmod 644 "$1"
}

# 기관. 자기 서명. 한 번 만들면 손님 · 수집기 신분증에 전부 이 도장이 찍힌다
make_ca() {
  make_key "$OUT/ca.key"
  openssl req -x509 -new -key "$OUT/ca.key" -days "$CA_DAYS" -sha256 \
    -subj "/CN=monimo local CA" \
    -config "$WORK/ca-req.cnf" -extensions ca_ext \
    -out "$OUT/ca.crt" 2>/dev/null
}

# 신분증 하나. $1 = 열쇠 · 신분증 경로(확장자 없이) · $2 = 이름(CN) · $3 = 확장 섹션(server_ext · client_ext)
make_leaf() {
  local base=$1 name=$2 ext=$3
  make_key "$base.key"
  openssl req -new -key "$base.key" -subj "/CN=$name" -out "$WORK/$name.csr" 2>/dev/null
  openssl x509 -req -in "$WORK/$name.csr" -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
    -days "$LEAF_DAYS" -sha256 -extfile "$WORK/ca-req.cnf" -extensions "$ext" \
    -out "$base.crt" 2>/dev/null
}

# 이미 만료된 손님. x509 -req 는 과거 날짜를 못 적어서 openssl ca 로 기간을 직접 준다 (2024-01-01 ~ 2024-01-02)
make_expired() {
  local base=$OUT/clients/expired
  make_key "$base.key"
  openssl req -new -key "$base.key" -subj "/CN=expired" -out "$WORK/expired.csr" 2>/dev/null
  : > "$WORK/index.txt"
  echo 01 > "$WORK/serial"
  openssl ca -batch -notext -config "$WORK/ca-sign.cnf" \
    -startdate 20240101000000Z -enddate 20240102000000Z \
    -in "$WORK/expired.csr" -out "$base.crt" 2>/dev/null
}

# openssl 설정. -addext 는 LibreSSL 구버전에 없어서 설정 파일로 준다
cat > "$WORK/ca-req.cnf" <<EOF
[req]
distinguished_name = dn
[dn]
[ca_ext]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
[server_ext]
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = $SERVER_SANS
[client_ext]
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
EOF
cat > "$WORK/ca-sign.cnf" <<EOF
[ca]
default_ca = local
[local]
database = $WORK/index.txt
serial = $WORK/serial
new_certs_dir = $WORK
certificate = $OUT/ca.crt
private_key = $OUT/ca.key
default_md = sha256
policy = any
x509_extensions = client_ext
[any]
commonName = supplied
[client_ext]
basicConstraints = CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
EOF

made=0
# 기관 열쇠가 없으면 그 도장으로 찍은 것들도 전부 쓸모없다. 통째로 다시 만든다
if [ ! -f "$OUT/ca.key" ] || [ ! -f "$OUT/ca.crt" ]; then
  rm -f "$OUT"/ca.* "$OUT"/server.* "$OUT"/clients/*
  make_ca; made=$((made + 1))
fi
if [ ! -f "$OUT/server.crt" ] || [ ! -f "$OUT/server.key" ]; then
  make_leaf "$OUT/server" collector server_ext; made=$((made + 1))
fi
for name in $CLIENTS; do
  if [ ! -f "$OUT/clients/$name.crt" ] || [ ! -f "$OUT/clients/$name.key" ]; then
    make_leaf "$OUT/clients/$name" "$name" client_ext; made=$((made + 1))
  fi
done
if [ ! -f "$OUT/clients/expired.crt" ] || [ ! -f "$OUT/clients/expired.key" ]; then
  make_expired; made=$((made + 1))
fi

# 쇼핑몰 손님의 에이전트 환경변수 묶음. 신분증이 아니라 그 경로를 적은 파일이라 늘 다시 써도 된다 (내용이 같으면 그대로)
mkdir -p "$OUT/env"
for name in $CLIENTS; do
  case "$name" in check|expired) continue ;; esac
  env_file=$OUT/env/$name.env
  content="# OTel Java Agent 의 에이전트 mTLS 설정 (monimo-backend scripts/certs/make-certs.sh 가 만든다, #159).
# 쇼핑몰 compose 가 MONIMO_OTLP_CERTS_DIR 이 차 있을 때 env_file 로 읽는다. 경로는 그 compose 가 /certs 로 마운트한 자리 기준
OTEL_EXPORTER_OTLP_ENDPOINT=https://collector:4317
OTEL_EXPORTER_OTLP_CERTIFICATE=/certs/ca.crt
OTEL_EXPORTER_OTLP_CLIENT_CERTIFICATE=/certs/clients/$name.crt
OTEL_EXPORTER_OTLP_CLIENT_KEY=/certs/clients/$name.key"
  [ -f "$env_file" ] && [ "$(cat "$env_file")" = "$content" ] || printf '%s\n' "$content" > "$env_file"
done

# 만든 것이 도장과 맞는지 확인한다. 만료된 벌은 일부러 틀리는 것이라 기간 검사를 빼고 도장만 본다
openssl verify -CAfile "$OUT/ca.crt" "$OUT/server.crt" "$OUT"/clients/*.crt > /dev/null 2>&1 \
  || openssl verify -no_check_time -CAfile "$OUT/ca.crt" "$OUT/server.crt" "$OUT"/clients/*.crt > /dev/null

if [ "$made" -eq 0 ]; then
  echo "인증서 이미 있음: $OUT (기관 · 수집기 · 손님 $(ls "$OUT/clients"/*.crt | wc -l | tr -d ' ')벌). 아무것도 안 했다"
else
  echo "인증서 ${made}벌 만듦: $OUT (기관 ${CA_DAYS}일 · 신분증 ${LEAF_DAYS}일 · 손님: $CLIENTS · expired)"
fi
