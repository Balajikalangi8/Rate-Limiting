# Token Bucket Rate Limiter — Spring Boot 3 / Java 17

A production-ready rate limiter using the **Token Bucket algorithm**, integrated
into a Spring Boot REST API via a Servlet Filter.

---

## Project Structure

```
rate-limiter/
├── pom.xml
├── README.md
├── test-rate-limit.sh                          ← manual curl smoke-test
└── src/
    ├── main/
    │   ├── java/com/example/ratelimiter/
    │   │   ├── RateLimiterApplication.java      ← @SpringBootApplication
    │   │   ├── config/
    │   │   │   └── FilterConfig.java            ← registers filter, URL patterns, order
    │   │   ├── controller/
    │   │   │   └── TestController.java          ← GET /api/test
    │   │   ├── filter/
    │   │   │   └── RateLimitFilter.java         ← Servlet filter, IP extraction
    │   │   ├── model/
    │   │   │   └── RateLimitProperties.java     ← @ConfigurationProperties
    │   │   └── service/
    │   │       ├── TokenBucket.java             ← core algorithm (thread-safe)
    │   │       └── RateLimiterService.java      ← per-IP bucket map + eviction
    │   └── resources/
    │       └── application.yml                  ← externalized config
    └── test/
        └── java/com/example/ratelimiter/
            ├── TokenBucketTest.java             ← unit tests (algorithm + concurrency)
            ├── RateLimiterServiceTest.java      ← service-level unit tests
            └── TestControllerIntegrationTest.java ← full Spring context integration
```

---

## How to Run

### Prerequisites

| Tool   | Version  |
|--------|----------|
| JDK    | 17+      |
| Maven  | 3.8+     |

### 1. Build

```bash
cd rate-limiter
mvn clean package -DskipTests
```

### 2. Run

```bash
java -jar target/rate-limiter-1.0.0.jar
```

Or with Maven directly:

```bash
mvn spring-boot:run
```

The server starts on **port 8080** by default.

### 3. Override configuration at runtime

```bash
java -jar target/rate-limiter-1.0.0.jar \
  --rate-limiter.capacity=20 \
  --rate-limiter.refill-rate=10 \
  --rate-limiter.per-ip=true
```

---

## Configuration Reference (`application.yml`)

| Key                                | Default | Description                                         |
|------------------------------------|---------|-----------------------------------------------------|
| `rate-limiter.capacity`            | `10`    | Max tokens per bucket (burst size)                  |
| `rate-limiter.refill-rate`         | `5`     | Tokens added per second                             |
| `rate-limiter.per-ip`              | `true`  | `true` = per-IP buckets; `false` = global bucket    |
| `rate-limiter.eviction-ttl-seconds`| `300`   | Evict idle buckets after N seconds (memory guard)   |

---

## How to Test Rate Limiting

### Automated tests

```bash
mvn test
```

Tests cover:
- Bucket correctness (burst, drain, refill)
- Thread-safety (200 concurrent goroutines)
- Integration (200 → 429 transition, per-IP isolation)

### Manual curl tests

```bash
# Single request (should return 200)
curl -i http://localhost:8080/api/test

# Rapid burst — 15 requests, expect 10×200 then 5×429
for i in $(seq 1 15); do
  echo -n "Request $i: "
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/test
done

# Test per-IP isolation
curl -H "X-Forwarded-For: 10.1.1.1" http://localhost:8080/api/test
curl -H "X-Forwarded-For: 10.2.2.2" http://localhost:8080/api/test
```

### Automated smoke-test script

```bash
./test-rate-limit.sh                       # default: localhost:8080
./test-rate-limit.sh http://myserver:8080  # custom host
```

### Expected responses

**200 OK** (within limit):
```json
{
  "status": 200,
  "message": "Request successful",
  "timestamp": "2026-04-18T10:00:00.000Z"
}
```

**429 Too Many Requests** (rate limit exceeded):
```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Please slow down."
}
```

---

## Algorithm: Token Bucket

```
capacity = 10 tokens
refill   = 5 tokens/second

Timeline:
  t=0s   bucket=[■■■■■■■■■■] 10 tokens
  req 1  bucket=[■■■■■■■■■ ] 9  → 200 OK
  ...
  req 10 bucket=[          ] 0  → 200 OK
  req 11 bucket=[          ] 0  → 429
  t=1s   bucket=[■■■■■     ] 5  (refilled)
  req 12 bucket=[■■■■      ] 4  → 200 OK
```

Key properties:
- **Burst**: allows up to `capacity` consecutive requests instantly
- **Sustained**: steady-state rate is bounded at `refillRate` req/s
- **Fair per-IP**: each client has an independent bucket

---

## Design Decisions

### Why Servlet Filter (not Interceptor / AOP)?

| Approach | Intercept Point | Overhead | Best For |
|---|---|---|---|
| **Servlet Filter** ✓ | Before DispatcherServlet | Lowest | HTTP-level, universal |
| HandlerInterceptor | After routing resolves | Medium | MVC-specific, per-controller |
| AOP `@Around` | Method proxy | Highest | Per-method annotation-driven |

A Servlet Filter was chosen because:
1. Rate limiting is HTTP-infrastructure concern, not a Spring MVC concern.
2. Rejected requests never consume DispatcherServlet or controller resources.
3. Direct access to raw `HttpServletRequest` makes IP extraction trivial.

### Why `ReentrantLock` over `AtomicLong`?

The token count and the last-refill timestamp must be updated **atomically together**. An `AtomicLong` CAS can only protect one field; two separate atomics would have a TOCTOU gap. A lock eliminates the race with minimal contention (critical section is nanoseconds).

### Memory management

A `@Scheduled` task evicts buckets idle longer than `eviction-ttl-seconds`. Without this, each unique IP (scanners, bots, ephemeral clients) permanently leaks a `TokenBucket` entry.
