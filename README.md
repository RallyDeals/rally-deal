# Deal Service

> GroupDeal Platform — Deal Service (the deal-state arbiter)

Deal Service is the **single source of truth for deal state**. No other service is allowed to flip a deal's status. It owns: deal creation rules, two capacity counters (`deal_stock` / `current_participants` / `authorized_count`), the state machine, and the internal timer that fires resolution at `end_time`.

## Quick Start

### Prerequisites
- Java 21+
- Maven 3.9+
- PostgreSQL 16+
- Kafka 3.7+ (for outbox relay)

### Run Locally
```bash
# Start dependencies (PostgreSQL, Kafka)
docker-compose up -d

# Build
mvn clean package -DskipTests

# Run
java -jar target/deal-service-0.1.0-SNAPSHOT.jar
```

### Run Tests
```bash
# Unit tests only
mvn test -Dtest=DealServiceTest

# All tests (requires Docker for Testcontainers)
mvn test
```

## Architecture

### State Machine
```
pending ──(first reserve-slot)──► active ──(authorized_count == deal_stock)──► succeeded
   │                                          │
   │                                          ├──(end_time, authorized_count >= min)──► succeeded
   │                                          └──(end_time, authorized_count < min)──► failed
   └──(seller cancels)──────────────────────► cancelled
```

Terminal states: `succeeded`, `failed`, `cancelled`

### Key Design Decisions
- **Authorized count drives success/failure** — `current_participants` is "intent to buy", `authorized_count` is "verified ability to pay"
- **Guarded updates** — all slot operations use conditional `UPDATE ... WHERE` so DB arbitrates concurrency
- **Outbox pattern** — state changes + events written in same transaction; Kafka relay publishes at-least-once
- **Idempotency** — all sync calls accept `requestId` header for retry safety

## API Reference

### External Endpoints (via API Gateway)

| Method | Endpoint | Auth | Description |
|--------|----------|------|-------------|
| POST | `/deals` | Seller | Create a deal |
| PATCH | `/deals/{id}` | Seller (owner) | Update deal (only while `pending`) |
| POST | `/deals/{id}/cancel` | Seller (owner) | Cancel deal (only while `pending`) |
| POST | `/deals/bulk` | Any | Fetch multiple deals by IDs |
| GET | `/deals` | Any | Browse/filter deals with pagination |
| GET | `/deals/{id}` | Any | Deal detail |
| GET | `/deals/analytics` | Any | Platform/seller analytics |
| GET | `/deals/seller-stats` | Seller | Seller-facing stats |

#### GET `/deals` Query Parameters
| Param | Type | Description |
|-------|------|-------------|
| `categories` | `UUID[]` | Filter by category UUIDs |
| `minPrice` | `BigDecimal` | Min deal price (inclusive) |
| `maxPrice` | `BigDecimal` | Max deal price (inclusive) |
| `sort` | `String` | `relevance` (default) \| `price-asc` \| `price-desc` \| `discount` \| `ending-soon` \| `most-joined` \| `newest` |
| `sellerId` | `UUID` | Filter by seller |
| `status` | `DealStatus[]` | Comma-separated; defaults to `ACTIVE,PENDING`; use `ALL` for all |
| `productId` | `UUID` | Filter by product |
| `page` | `int` | 0-based page (default 0) |
| `limit` | `int` | Page size (default 20) |

**Default sort: `relevance`** — composite score (discount 40% + momentum 30% + urgency 30%)

### Internal Endpoints (service-to-service, sync)

| Method | Endpoint | Caller | Description |
|--------|----------|--------|-------------|
| POST | `/internal/deals/{id}/reserve-slot` | Participation Service | Reserve slot on join |
| GET | `/internal/deals/{id}/check-leave-eligible` | Participation Service | Read-only leave permission check |
| POST | `/internal/deals/{id}/release-slot` | Order Service | Release pre-authorization slot |
| POST | `/internal/deals/{id}/authorize-slot` | Order Service | Mark slot as payment-authorized |
| POST | `/internal/deals/{id}/release-authorized-slot` | Order Service | Release authorized slot |
| GET | `/internal/deals/product/{productId}/has-active-deals` | Catalog Service | Check if product has active deals |

All internal endpoints require `requestId` header for idempotency.

## DTOs

### DealOverview (GET `/deals`)
```json
{
  "id": "uuid",
  "productId": "uuid",
  "sellerId": "uuid",
  "categoryId": "uuid",
  "originalPrice": 199.99,
  "dealPrice": 149.99,
  "dealStock": 100,
  "currentParticipants": 68,
  "minParticipants": 40,
  "authorizedCount": 63,
  "status": "active",
  "durationMinutes": 1440,
  "startTime": "2026-07-12T10:15:00Z",
  "endTime": "2026-07-13T10:15:00Z",
  "createdAt": "2026-07-12T10:00:00Z"
}
```

### DealDetails (GET `/deals/{id}`)
Same fields as `DealOverview`.

### DealAnalyticsResponse (GET `/deals/analytics`)
```json
{
  "totalDeals": 1542,
  "dealsThisMonth": 87,
  "activeDeals": 23,
  "dealsToday": 5,
  "completedDeals": 1204,
  "successRate": 87.4
}
```

### SellerStatsResponse (GET `/deals/seller-stats`)
```json
{
  "activeDealCnt": 3,
  "totalRevenue": "299.98",
  "participantsJoined": 2,
  "avgCompletionRate": 1.0
}
```

### SlotResponse (internal slot operations)
```json
{
  "success": true,
  "dealId": "uuid",
  "dealPrice": 149.99,
  "currentParticipants": 64,
  "authorizedCount": 59,
  "dealStock": 100,
  "status": "active",
  "startTime": "2026-07-12T10:15:00Z",
  "endTime": "2026-07-13T10:15:00Z"
}
```

## Database

### Tables
- `deals` — core deal state with counters and state machine
- `deal_outbox` — transactional outbox for Kafka events
- `deal_slot_requests` — idempotency keys for sync calls

### Indexes
```sql
CREATE INDEX idx_deals_status ON deals (status);
CREATE INDEX idx_deals_seller_id ON deals (seller_id, status);
CREATE INDEX idx_deals_product_id ON deals (product_id);
CREATE INDEX idx_deals_category_id ON deals (category_id);
CREATE INDEX idx_deals_category_status ON deals (category_id, status);
CREATE INDEX idx_deals_active_end_time ON deals (end_time) WHERE status = 'active';
```

## Events (Kafka)

All events published via outbox, keyed by `deal_id`:

- `deal.created` — deal created
- `deal.cancelled` — deal cancelled (Inventory releases stock)
- `deal.succeeded` — deal succeeded (Order captures, Notification emails, Inventory finalizes)
- `deal.failed` — deal failed (Order voids, Notification emails, Inventory releases)

## Communication

### Sync (Deal Service as callee)
| Caller | Endpoint | When |
|--------|----------|------|
| Participation Service | `reserve-slot` | Buyer joins |
| Participation Service | `check-leave-eligible` | Buyer wants to leave |
| Order Service | `release-slot` | Payment declined pre-auth |
| Order Service | `authorize-slot` | Payment authorized |
| Order Service | `release-authorized-slot` | Participant left post-auth |
| Catalog Service | `has-active-deals` | Before product edit/delete |

### Sync (Deal Service as caller)
| Callee | When |
|--------|------|
| Catalog Service `GET /products/{id}` | Deal creation (ownership + price snapshot) |
| Inventory Service `POST /inventory/{id}/reserve` | Deal creation (stock reservation) |

## Configuration

Key properties (`application.yml`):
```yaml
groupdeal:
  clients:
    catalog:
      base-url: http://catalog-service:8083
      stub: false
    inventory:
      base-url: http://inventory-service:8087
      stub: false

spring:
  kafka:
    bootstrap-servers: localhost:9092
  datasource:
    url: jdbc:postgresql://localhost:5432/groupdeal_deals
```

## Testing

```bash
# Unit tests (mocked dependencies)
mvn test -Dtest=DealServiceTest

# Integration tests (Testcontainers PostgreSQL)
mvn test -Dtest=DealLifecycleIntegrationTest
mvn test -Dtest=DealListIntegrationTest
mvn test -Dtest=DealSellerStatsIntegrationTest

# Concurrency test
mvn test -Dtest=DealReserveSlotConcurrencyTest
```

## Design Doc

Full specification: [deal-service.md](deal-service.md)

## License

Internal — GroupDeal Platform