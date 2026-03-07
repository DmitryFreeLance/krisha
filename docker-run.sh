#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="roof-bot"
CONTAINER_NAME="roof-bot"

# Build image
mvn -q -DskipTests package

docker build -t "$IMAGE_NAME" .

# Run container
# Replace BOT_TOKEN, BOT_USERNAME, ADMIN_IDS with your values.
# Put your real 1.jpg рядом с этим файлом.
docker run -d --name "$CONTAINER_NAME" \
  -e BOT_TOKEN="YOUR_TOKEN" \
  -e BOT_USERNAME="YOUR_BOT_USERNAME" \
  -e ADMIN_IDS="123456789" \
  -e PHOTO_PATH="/app/1.jpg" \
  -v "$(pwd)/1.jpg:/app/1.jpg:ro" \
  -v "$(pwd)/data:/app/data" \
  "$IMAGE_NAME"
