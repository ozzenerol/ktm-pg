-- wrk mix for the soak test: 85% GET /users/{id}, 9% GET /users,
-- 5% POST /users with one of the 10000 seeded emails (a 409: the unique
-- violation path, a failed insert each time), 1% DELETE /users/{id} of an
-- id past the seeded ones (a 404). The table stays at its 10000 rows, so
-- the GETs find their user, for any length of run.
-- Prints one JSON line at the end: requests, non-2xx/404/409 statuses, socket errors.
local counter = 0
local thread_id = 0
local threads = {}

function setup(thread)
  thread:set("tid", #threads + 1)
  table.insert(threads, thread)
end

function init(args)
  bad = 0
  math.randomseed(os.time() * 1000 + tid)
end

function request()
  counter = counter + 1
  local r = math.random(100)
  if r <= 85 then
    return wrk.format("GET", "/users/" .. math.random(10000))
  elseif r <= 94 then
    return wrk.format("GET", "/users")
  elseif r <= 99 then
    local body = string.format('{"name":"soak","email":"u%d@x"}', math.random(10000))
    return wrk.format("POST", "/users", {["Content-Type"] = "application/json"}, body)
  else
    return wrk.format("DELETE", "/users/" .. math.random(10001, 20000))
  end
end

function response(status, headers, body)
  if status >= 500 or status == 400 then bad = bad + 1 end
end

function done(summary, latency, requests)
  local b = 0
  for _, t in ipairs(threads) do b = b + t:get("bad") end
  io.write(string.format('{"requests":%d,"bad":%d,"errors":%d,"timeouts":%d,"p99_us":%d}\n',
    summary.requests, b, summary.errors.connect + summary.errors.read + summary.errors.write + summary.errors.status,
    summary.errors.timeout, latency:percentile(99)))
end
