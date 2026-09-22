-- 가짜 신호 데이터: 쇼핑몰 서비스 4개가 최근 1시간 동안 보낸 것처럼 만든다.
-- 넣는 법: ./scripts/seed-clickhouse.sh   (기존 데이터를 비우고 다시 넣는다)
--
-- 원본 표(spans · metrics_raw · logs · thread_dumps)에만 넣는다. 집계 7표는 MV가 알아서 채운다.
-- 모양은 OTel Java Agent 2.x 가 실제로 보내는 형식에 맞춘다.
--   - 서버 스팬 이름 = "메서드 경로틀" (POST /orders), HTTP 클라이언트 스팬 이름 = 메서드만 (POST)
--   - HTTP 꼬리표 = 안정판 이름 (http.request.method · http.route · http.response.status_code · url.full)
--   - DB 꼬리표 = 에이전트 2.x 기본값인 옛 이름 (db.system · db.name · db.statement · db.operation)
--   - 서버 스팬은 4xx 여도 status UNSET, 5xx 만 ERROR. 클라이언트 스팬은 4xx 부터 ERROR.
--   - 서비스 이름은 monimo-deploy 에서 쓸 이름 그대로 (shop-gateway · shop-order · shop-payment · shop-inventory)
--
-- 들어가는 이야기
--   - 요청 3만 건 (1시간, 초당 약 8건). 70%는 주문 생성, 30%는 주문 조회
--   - 주문 생성: gateway → order → inventory(재고 예약) → payment(결제 승인) → 각자 MySQL
--   - 결제 5xx: 평소 2%, 5~15분 전에는 15% (5XX_RATE 경보 시험용)
--   - 결제 1%는 0.8~2.5초로 느리다 (스캐터에서 위로 튀는 점)
--   - 주문 조회의 3%는 없는 주문이라 404 (4XX_RATE 시험용)
--   - shop-payment 2번 파드는 힙이 1시간 동안 꾸준히 늘어난다 (인스펙터 시험용)

-- 1) 스팬 ---------------------------------------------------------------------------------------
INSERT INTO monimo.spans
(
    trace_id, span_id, parent_span_id, start_time, duration_ns,
    service_name, agent_id, span_name, span_kind, status_code, http_status,
    peer_address, peer_service, attributes,
    `events.ts`, `events.name`, `events.attributes`
)
WITH
    -- 요청 한 건의 공통 값 (n 으로부터 결정되는 가짜 난수라서 다시 넣어도 같은 모양이 나온다)
    now64(9) AS anchor,
    addMilliseconds(anchor, -toInt64(cityHash64(n, 't') % 3600000)) AS t0,
    (cityHash64(n, 'route') % 100) < 70 AS is_create,
    dateDiff('minute', t0, anchor) BETWEEN 5 AND 14 AS in_incident,
    (cityHash64(n, 'payfail') % 100) < if(in_incident, 15, 2) AS pay_fail,
    (cityHash64(n, 'notfound') % 100) < 3 AS not_found,
    (cityHash64(n, 'slow') % 100) = 0 AS pay_slow,
    toUInt64(1000000) AS ms,
    -- 구간별 걸린 시간 (나노초)
    (2 + cityHash64(n, 'd1') % 10) * ms AS d_inv_db,
    d_inv_db + (2 + cityHash64(n, 'd2') % 6) * ms AS d_inv,
    if(pay_slow, (800 + cityHash64(n, 'd3') % 1700) * ms, (3 + cityHash64(n, 'd3') % 15) * ms) AS d_pay_db,
    d_pay_db + (3 + cityHash64(n, 'd4') % 8) * ms AS d_pay,
    (1 + cityHash64(n, 'd5') % 6) * ms AS d_sel_db,
    if(is_create, d_inv + d_pay + (4 + cityHash64(n, 'd6') % 12) * ms, d_sel_db + (2 + cityHash64(n, 'd6') % 6) * ms) AS d_order,
    d_order + (2 + cityHash64(n, 'd7') % 6) * ms AS d_gw,
    -- 응답 코드
    toUInt16(multiIf(is_create AND pay_fail, 500, NOT is_create AND not_found, 404, is_create, 201, 200)) AS code,
    toUInt16(if(pay_fail, 500, 200)) AS pay_code,
    toString(100000 + n) AS order_id,
    -- 파드 (서비스마다 2대, 요청마다 한 대로 나뉜다)
    concat('shop-gateway-6d8f7b-',   ['k2x9p', 'r7mq4'][1 + cityHash64(n, 'gw')  % 2]) AS a_gw,
    concat('shop-order-7c9d5f-',     ['2xk8p', 'b4n7t'][1 + cityHash64(n, 'or')  % 2]) AS a_or,
    concat('shop-inventory-5b6c8d-', ['h3v2w', 'q9z5c'][1 + cityHash64(n, 'in')  % 2]) AS a_in,
    concat('shop-payment-84f6c9-',   ['m5t1s', 'x8d3f'][1 + cityHash64(n, 'pay') % 2]) AS a_pay,
    -- 스팬 목록: (번호, 부모 번호, 시작 오프셋ns, 걸린ns, 서비스, 파드, 이름, 종류, 상태, HTTP코드, 상대주소, 상대서비스, 꼬리표, 예외여부)
    if(is_create,
        [
            (1, 0, 0,                          d_gw,     'shop-gateway',   a_gw,  'POST /api/orders',        'SERVER', if(code >= 500, 'ERROR', 'UNSET'), code,     '', '',
                map('http.request.method', 'POST', 'http.route', '/api/orders', 'url.path', '/api/orders', 'http.response.status_code', toString(code)), 0),
            (2, 1, ms,                         d_order + ms, 'shop-gateway', a_gw,  'POST',                    'CLIENT', if(code >= 400, 'ERROR', 'UNSET'), code,     'shop-order:8080', 'shop-order',
                map('http.request.method', 'POST', 'url.full', 'http://shop-order:8080/orders', 'server.address', 'shop-order', 'server.port', '8080', 'http.response.status_code', toString(code)), 0),
            (3, 2, 2 * ms,                     d_order,  'shop-order',     a_or,  'POST /orders',            'SERVER', if(code >= 500, 'ERROR', 'UNSET'), code,     '', '',
                map('http.request.method', 'POST', 'http.route', '/orders', 'url.path', '/orders', 'http.response.status_code', toString(code)), 0),
            (4, 3, 3 * ms,                     d_inv + ms, 'shop-order',   a_or,  'POST',                    'CLIENT', 'UNSET',                           200,      'shop-inventory:8080', 'shop-inventory',
                map('http.request.method', 'POST', 'url.full', 'http://shop-inventory:8080/inventory/reserve', 'server.address', 'shop-inventory', 'server.port', '8080', 'http.response.status_code', '200'), 0),
            (5, 4, 4 * ms,                     d_inv,    'shop-inventory', a_in,  'POST /inventory/reserve', 'SERVER', 'UNSET',                           200,      '', '',
                map('http.request.method', 'POST', 'http.route', '/inventory/reserve', 'url.path', '/inventory/reserve', 'http.response.status_code', '200'), 0),
            (6, 5, 5 * ms,                     d_inv_db, 'shop-inventory', a_in,  'UPDATE shop.inventory',   'CLIENT', 'UNSET',                           0,        'mysql:3306', '',
                map('db.system', 'mysql', 'db.name', 'shop', 'db.operation', 'UPDATE', 'db.sql.table', 'inventory', 'db.statement', 'UPDATE inventory SET reserved = reserved + ? WHERE product_id = ?'), 0),
            (7, 3, 5 * ms + d_inv,             d_pay + ms, 'shop-order',   a_or,  'POST',                    'CLIENT', if(pay_fail, 'ERROR', 'UNSET'),    pay_code, 'shop-payment:8080', 'shop-payment',
                map('http.request.method', 'POST', 'url.full', 'http://shop-payment:8080/payments', 'server.address', 'shop-payment', 'server.port', '8080', 'http.response.status_code', toString(pay_code)), 0),
            (8, 7, 6 * ms + d_inv,             d_pay,    'shop-payment',   a_pay, 'POST /payments',          'SERVER', if(pay_fail, 'ERROR', 'UNSET'),    pay_code, '', '',
                map('http.request.method', 'POST', 'http.route', '/payments', 'url.path', '/payments', 'http.response.status_code', toString(pay_code)), toUInt8(pay_fail)),
            (9, 8, 7 * ms + d_inv,             d_pay_db, 'shop-payment',   a_pay, 'INSERT shop.payments',    'CLIENT', 'UNSET',                           0,        'mysql:3306', '',
                map('db.system', 'mysql', 'db.name', 'shop', 'db.operation', 'INSERT', 'db.sql.table', 'payments', 'db.statement', 'INSERT INTO payments (order_id, amount, status) VALUES (?, ?, ?)'), 0)
        ],
        [
            (1, 0, 0,                          d_gw,     'shop-gateway',   a_gw,  'GET /api/orders/{id}',    'SERVER', 'UNSET',                           code,     '', '',
                map('http.request.method', 'GET', 'http.route', '/api/orders/{id}', 'url.path', concat('/api/orders/', order_id), 'http.response.status_code', toString(code)), 0),
            (2, 1, ms,                         d_order + ms, 'shop-gateway', a_gw,  'GET',                     'CLIENT', if(code >= 400, 'ERROR', 'UNSET'), code,     'shop-order:8080', 'shop-order',
                map('http.request.method', 'GET', 'url.full', concat('http://shop-order:8080/orders/', order_id), 'server.address', 'shop-order', 'server.port', '8080', 'http.response.status_code', toString(code)), 0),
            (3, 2, 2 * ms,                     d_order,  'shop-order',     a_or,  'GET /orders/{id}',        'SERVER', 'UNSET',                           code,     '', '',
                map('http.request.method', 'GET', 'http.route', '/orders/{id}', 'url.path', concat('/orders/', order_id), 'http.response.status_code', toString(code)), 0),
            (4, 3, 3 * ms,                     d_sel_db, 'shop-order',     a_or,  'SELECT shop.orders',      'CLIENT', 'UNSET',                           0,        'mysql:3306', '',
                map('db.system', 'mysql', 'db.name', 'shop', 'db.operation', 'SELECT', 'db.sql.table', 'orders', 'db.statement', 'SELECT * FROM orders WHERE id = ?'), 0)
        ]
    ) AS span_list,
    arrayJoin(span_list) AS s,
    lower(concat(leftPad(hex(cityHash64(n, 'trace', 1)), 16, '0'), leftPad(hex(cityHash64(n, 'trace', 2)), 16, '0'))) AS tid,
    addNanoseconds(t0, toInt64(s.3)) AS s_start
SELECT
    tid                                                                      AS trace_id,
    lower(leftPad(hex(cityHash64(n, 'span', s.1)), 16, '0'))                 AS span_id,
    if(s.2 = 0, '', lower(leftPad(hex(cityHash64(n, 'span', s.2)), 16, '0'))) AS parent_span_id,
    s_start                                                                  AS start_time,
    toUInt64(s.4)                                                            AS duration_ns,
    s.5, s.6, s.7, s.8, s.9, s.10, s.11, s.12,
    CAST(s.13, 'Map(LowCardinality(String), String)')                        AS attributes,
    if(s.14 = 1, [addNanoseconds(s_start, toInt64(s.4) - 1000000)], [])     AS `events.ts`,
    if(s.14 = 1, ['exception'], [])                                          AS `events.name`,
    if(s.14 = 1, [map(
        'exception.type', 'com.monimo.shop.payment.PaymentGatewayException',
        'exception.message', '카드사 승인 응답 시간 초과 (PG_TIMEOUT)',
        'exception.stacktrace', 'com.monimo.shop.payment.PaymentGatewayException: 카드사 승인 응답 시간 초과 (PG_TIMEOUT)\n\tat com.monimo.shop.payment.PaymentService.approve(PaymentService.kt:42)\n\tat com.monimo.shop.payment.PaymentController.create(PaymentController.kt:27)'
    )], [])                                                                  AS `events.attributes`
FROM (SELECT number AS n FROM numbers(30000));

-- 2) 메트릭 (15초마다, 파드 8대) ------------------------------------------------------------------
-- 이름은 OTel JVM 규칙 그대로. series_hash 는 attributes 를 숫자로 접은 값.
INSERT INTO monimo.metrics_raw (service_name, agent_id, metric_name, series_hash, attributes, ts, value)
WITH
    toStartOfInterval(now(), INTERVAL 15 SECOND) AS anchor,
    [
        ('shop-gateway',   'shop-gateway-6d8f7b-k2x9p'),   ('shop-gateway',   'shop-gateway-6d8f7b-r7mq4'),
        ('shop-order',     'shop-order-7c9d5f-2xk8p'),     ('shop-order',     'shop-order-7c9d5f-b4n7t'),
        ('shop-inventory', 'shop-inventory-5b6c8d-h3v2w'), ('shop-inventory', 'shop-inventory-5b6c8d-q9z5c'),
        ('shop-payment',   'shop-payment-84f6c9-m5t1s'),   ('shop-payment',   'shop-payment-84f6c9-x8d3f')
    ] AS pods,
    arrayJoin(pods) AS pod,
    arrayJoin(range(240)) AS step,                                   -- 1시간 = 15초 × 240
    anchor - toIntervalSecond(15 * (239 - step)) AS t,
    (cityHash64(pod.2, step) % 1000) / 1000.0 AS jitter,             -- 0.0 ~ 1.0
    pod.2 = 'shop-payment-84f6c9-x8d3f' AS leaky,
    [
        ('jvm.cpu.recent_utilization', map(), 0.08 + 0.25 * jitter),
        ('jvm.memory.used', map('jvm.memory.type', 'heap', 'jvm.memory.pool.name', 'G1 Old Gen'),
            if(leaky, 300e6 + 900e6 * step / 239, 280e6 + 60e6 * jitter)),
        ('jvm.memory.used', map('jvm.memory.type', 'heap', 'jvm.memory.pool.name', 'G1 Eden Space'), 40e6 + 160e6 * jitter),
        ('jvm.memory.limit', map('jvm.memory.type', 'heap', 'jvm.memory.pool.name', 'G1 Old Gen'), 1536e6),
        ('jvm.gc.duration', map('jvm.gc.name', 'G1 Young Generation', 'jvm.gc.action', 'end of minor GC'), 0.004 + 0.02 * jitter),
        ('jvm.thread.count', map(), toFloat64(38 + cityHash64(pod.2, step, 'th') % 12))
    ] AS series,
    arrayJoin(series) AS m
SELECT
    pod.1, pod.2, m.1,
    cityHash64(toString(m.2))                            AS series_hash,
    CAST(m.2, 'Map(LowCardinality(String), String)')      AS attributes,
    t, m.3
FROM system.one;

-- 3) 로그 ----------------------------------------------------------------------------------------
-- 주문 생성마다 INFO 한 줄, 결제 실패마다 ERROR 한 줄. trace_id 는 스팬과 같은 규칙으로 만들어 콜트리와 이어진다.
INSERT INTO monimo.logs (trace_id, span_id, ts, service_name, agent_id, logger, thread, level, message, attributes)
SELECT
    trace_id,
    span_id,
    toDateTime64(start_time + toIntervalNanosecond(duration_ns), 3),
    service_name,
    agent_id,
    multiIf(service_name = 'shop-payment', 'com.monimo.shop.payment.PaymentService',
            service_name = 'shop-order',   'com.monimo.shop.order.OrderService',
                                           'com.monimo.shop.gateway.RequestLoggingFilter'),
    concat('http-nio-8080-exec-', toString(1 + cityHash64(span_id) % 20)),
    if(status_code = 'ERROR', 'ERROR', if(http_status BETWEEN 400 AND 499, 'WARN', 'INFO')),
    multiIf(status_code = 'ERROR' AND service_name = 'shop-payment', '결제 승인 실패: 카드사 승인 응답 시간 초과 (PG_TIMEOUT)',
            status_code = 'ERROR' AND service_name = 'shop-order',   '주문 생성 실패: shop-payment 가 500 을 돌려줌',
            http_status = 404,                                      '주문을 찾을 수 없음',
            span_name = 'POST /orders',                             '주문 생성 완료',
            span_name = 'POST /payments',                           '결제 승인 완료',
                                                                    concat(span_name, ' ', toString(http_status))),
    map('http.route', attributes['http.route'])
FROM monimo.spans
WHERE span_kind = 'SERVER' AND service_name IN ('shop-order', 'shop-payment')
   OR (span_kind = 'SERVER' AND service_name = 'shop-gateway' AND http_status >= 400);

-- 4) 스레드 덤프 (화면 확인용 2건) ------------------------------------------------------------------
INSERT INTO monimo.thread_dumps (agent_id, service_name, dump_uuid, requested_by, requested_at, thread_count, dump)
VALUES
    ('shop-payment-84f6c9-x8d3f', 'shop-payment', '3f2b8c1e-6a4d-4e2f-9b1a-7c5d8e9f0a12', 'admin@monimo.dev',
     now64(3) - INTERVAL 12 MINUTE, 47,
     '"http-nio-8080-exec-7" #41 daemon prio=5 WAITING\n\tat java.base/jdk.internal.misc.Unsafe.park(Native Method)\n\tat com.monimo.shop.payment.PgClient.approve(PgClient.kt:58)\n\tat com.monimo.shop.payment.PaymentService.approve(PaymentService.kt:42)\n\n"http-nio-8080-exec-8" #42 daemon prio=5 RUNNABLE\n\tat java.base/sun.nio.ch.SocketDispatcher.read0(Native Method)\n\tat com.mysql.cj.protocol.ReadAheadInputStream.fill(ReadAheadInputStream.java:107)'),
    ('shop-order-7c9d5f-2xk8p', 'shop-order', '9a7e4d2c-1b3f-4a5e-8c6d-2e4f6a8b0c34', 'admin@monimo.dev',
     now64(3) - INTERVAL 40 MINUTE, 39,
     '"http-nio-8080-exec-3" #37 daemon prio=5 RUNNABLE\n\tat com.monimo.shop.order.OrderService.create(OrderService.kt:31)\n\n"HikariPool-1 housekeeper" #22 daemon prio=5 TIMED_WAITING\n\tat java.base/jdk.internal.misc.Unsafe.park(Native Method)');
