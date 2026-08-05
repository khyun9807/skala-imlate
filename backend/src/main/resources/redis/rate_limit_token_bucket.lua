-- =====================================================================
-- imlate rate limit — 토큰 버킷 (원자적, 1 RTT)
--
-- KEYS[1] : 버킷 키            (imlate:rl:{scope}:{clientIp})
-- ARGV[1] : capacity           버킷 최대 토큰 수
-- ARGV[2] : refillTokens       refillPeriodMs 마다 채워지는 토큰 수
-- ARGV[3] : refillPeriodMs     보충 주기(밀리초)
-- ARGV[4] : nowMs              호출자가 넘긴 현재 시각(epoch milli)
-- ARGV[5] : requested          이번 요청이 소비할 토큰 수(보통 1)
--
-- 반환 : {allowed(0|1), remaining, retryAfterMs, resetMs}  — 모두 정수
--
-- 설계 메모
--  * Redis 의 TIME 대신 nowMs 를 받는다. 스크립트가 결정적(deterministic)이어야
--    복제/AOF 에서 안전하고, 서버 간 시계 차이를 애플리케이션 Clock 하나로 통일할 수 있다.
--  * 리필은 경과 시간에 비례한 "부분 리필"이다. (elapsed * refillTokens / refillPeriodMs)
--  * HASH 필드 tokens(잔여 토큰, 소수 허용), ts(마지막 갱신 시각)만 사용한다.
--  * PEXPIRE 는 "버킷이 가득 찰 때까지 필요한 시간 + 보충 주기 1회분"으로 잡는다.
--    가득 찬 버킷은 어차피 초기 상태와 동일하므로 만료되어도 판정 결과가 달라지지 않는다.
-- =====================================================================

local key            = KEYS[1]
local capacity       = tonumber(ARGV[1])
local refillTokens   = tonumber(ARGV[2])
local refillPeriodMs = tonumber(ARGV[3])
local nowMs          = tonumber(ARGV[4])
local requested      = tonumber(ARGV[5])

-- 방어적 보정: 잘못된 설정으로 0 나눗셈이 나지 않도록 한다.
if capacity == nil or capacity <= 0 then capacity = 1 end
if refillTokens == nil or refillTokens <= 0 then refillTokens = capacity end
if refillPeriodMs == nil or refillPeriodMs <= 0 then refillPeriodMs = 1000 end
if nowMs == nil then nowMs = 0 end
if requested == nil or requested < 0 then requested = 1 end

-- 밀리초당 리필 토큰 수
local ratePerMs = refillTokens / refillPeriodMs

local stored = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(stored[1])
local ts     = tonumber(stored[2])

if tokens == nil or ts == nil then
  -- 최초 요청: 버킷은 가득 찬 상태로 시작한다.
  tokens = capacity
  ts = nowMs
end

-- 시계 역행(NTP 보정 등) 방어: 미래 시각이 저장돼 있으면 기준을 현재로 당긴다.
if nowMs < ts then
  ts = nowMs
end

local elapsed = nowMs - ts
if elapsed > 0 then
  tokens = tokens + (elapsed * ratePerMs)
  if tokens > capacity then
    tokens = capacity
  end
  ts = nowMs
end

local allowed = 0
if tokens >= requested then
  allowed = 1
  tokens = tokens - requested
end

-- 차단 시 부족분을 채우는 데 필요한 시간
local retryAfterMs = 0
if allowed == 0 then
  local deficit = requested - tokens
  if deficit < 0 then deficit = 0 end
  retryAfterMs = math.ceil(deficit / ratePerMs)
  if retryAfterMs < 1 then retryAfterMs = 1 end
end

-- 버킷이 다시 가득 찰 때까지 남은 시간
local missing = capacity - tokens
local resetMs = 0
if missing > 0 then
  resetMs = math.ceil(missing / ratePerMs)
end

-- 다중 필드 HSET 은 Redis 4.0+ 에서만 되므로 호환성을 위해 HMSET 을 쓴다(1 커맨드 유지).
redis.call('HMSET', key, 'tokens', tokens, 'ts', ts)
redis.call('PEXPIRE', key, resetMs + refillPeriodMs)

local remaining = math.floor(tokens)
if remaining < 0 then remaining = 0 end

-- Redis 는 Lua number 를 정수로 절삭해 반환하므로 명시적으로 정수화한다.
return { allowed, remaining, math.floor(retryAfterMs), math.floor(resetMs) }
