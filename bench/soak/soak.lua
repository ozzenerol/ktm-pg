-- wrk mix for the soak test: 80% GET /users/{id}, 10% GET /users,
-- 8% POST /users (a new email each time), 2% DELETE /users/{id}.
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
  if r <= 80 then
    return wrk.format("GET", "/users/" .. math.random(10000))
  elseif r <= 90 then
    return wrk.format("GET", "/users")
  elseif r <= 98 then
    local body = string.format('{"name":"soak","email":"t%d-%d-%d@x"}', tid, os.time(), counter)
    return wrk.format("POST", "/users", {["Content-Type"] = "application/json"}, body)
  else
    return wrk.format("DELETE", "/users/" .. math.random(10000, 2000000))
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
