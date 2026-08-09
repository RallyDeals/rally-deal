#!/usr/bin/env bash

set -e

# Project root directory
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo "=========================================="
echo "🚀 Starting GroupDeal Microservices Stack"
echo "=========================================="

# 1. Start PostgreSQL & Kafka using docker-compose if not already up
echo "📦 Step 1: Starting PostgreSQL & Kafka containers..."
docker-compose up -d postgres kafka

# 2. Build the deal-service Docker image
echo "🔨 Step 2: Building deal-service Docker image..."
docker build -t code-v1_deal-service:latest .

# 3. Remove old deal-service container if running
echo "🧹 Step 3: Cleaning up old container instances..."
docker rm -f code-v1_deal-service_1 2>/dev/null || true

# 4. Run the deal-service container
echo "▶️ Step 4: Starting deal-service container..."
docker run -d \
  --name code-v1_deal-service_1 \
  --network code-v1_default \
  -p 8081:8081 \
  -e DB_HOST=postgres \
  -e DB_PORT=5432 \
  -e DB_NAME=groupdeal_deals \
  -e DB_USER=groupdeal \
  -e DB_PASSWORD=groupdeal \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:29092 \
  code-v1_deal-service:latest

# 5. Wait for Spring Boot application startup
echo "⏳ Step 5: Waiting for Java / Spring Boot application to complete startup..."
until curl -s http://localhost:8081/actuator/health | grep -q '"status":"UP"'; do
  sleep 2
  echo -n "."
done
echo ""

echo "=========================================="
echo "✅ All services successfully launched and healthy!"
echo "=========================================="
echo "🔗 Swagger UI:       http://localhost:8081/swagger-ui.html"
echo "🔗 Health Endpoint:  http://localhost:8081/actuator/health"
echo "=========================================="
