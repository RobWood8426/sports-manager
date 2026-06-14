#!/bin/bash
# Start the Datomic transactor in Docker.

set -e

cd "$(dirname "$0")/.."

echo "Starting Datomic transactor..."

if [ ! -d "datomic-pro-1.0.7469" ]; then
    echo "Datomic not found, downloading..."
    ./scripts/download-datomic.sh
fi

echo "Building Docker image..."
docker compose -f docker-compose.datomic.yml build

echo "Starting container..."
docker compose -f docker-compose.datomic.yml up -d

echo ""
echo "Datomic transactor starting on port 4334"
echo ""
echo "Logs:  docker compose -f docker-compose.datomic.yml logs -f"
echo "Stop:  docker compose -f docker-compose.datomic.yml down"
echo ""
echo "To connect the REPL, set in .env:"
echo "  SM_DATOMIC_USE_TRANSACTOR=true"
echo "  SM_DATOMIC_TRANSACTOR_HOST=localhost"
echo "  SM_DATOMIC_TRANSACTOR_PORT=4334"
