#!/usr/bin/env bash

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo "🛑 Stopping GroupDeal Microservices Stack..."
docker rm -f code-v1_deal-service_1 2>/dev/null || true
docker-compose stop postgres kafka

echo "✅ All services stopped."
