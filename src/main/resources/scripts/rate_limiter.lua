local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2]) -- Refill rate (tokens per second)
local requested = tonumber(ARGV[3])    -- Number of tokens requested (usually 1)
local now = tonumber(ARGV[4])         -- Current time in epoch milliseconds

-- Retrieve current bucket state from Redis hash map
local data = redis.call('HMGET', key, 'tokens', 'last_updated')
local tokens = tonumber(data[1])
local last_updated = tonumber(data[2])

if not tokens then
    -- Bucket is brand new, initialize it to full capacity
    tokens = capacity
    last_updated = now
else
    -- Calculate tokens refilled based on elapsed time in milliseconds
    local elapsed = math.max(0, now - last_updated)
    -- refill_rate is tokens/sec, so tokens per ms is refill_rate / 1000.0
    local refilled = elapsed * (refill_rate / 1000.0)
    tokens = math.min(capacity, tokens + refilled)
end

local allowed = 0
-- Check if bucket has sufficient tokens
if tokens >= requested then
    allowed = 1
    tokens = tokens - requested
    -- Update both tokens and timestamp
    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', now)
else
    -- Just update tokens (which may have refilled but not enough), keep old timestamp or update it
    -- Note: We update the token count to store any partially refilled tokens, but update timestamp to now 
    -- so that we don't double-refill in subsequent checks.
    redis.call('HMSET', key, 'tokens', tokens, 'last_updated', now)
end

-- Set TTL of 1 hour to prevent orphaned keys from consuming memory indefinitely
redis.call('EXPIRE', key, 3600)

-- Return status (1 = allowed, 0 = blocked) and remaining tokens
return { allowed, tokens }
