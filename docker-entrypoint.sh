#!/bin/sh
set -e

# If the Firebase service account JSON is supplied as an env var (Fly secret),
# write it to a temp file and point SM_FIREBASE_CREDENTIALS at it.
if [ -n "$SM_FIREBASE_CREDENTIALS_JSON" ]; then
  echo "$SM_FIREBASE_CREDENTIALS_JSON" > /tmp/firebase-sa.json
  export SM_FIREBASE_CREDENTIALS=/tmp/firebase-sa.json
fi

exec java -Xmx256m -jar app.jar
