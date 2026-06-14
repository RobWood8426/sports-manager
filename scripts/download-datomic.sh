#!/bin/bash
# Download Datomic Pro for Docker build.
# If stub-server already has it, symlink rather than re-download.

set -e

VERSION="1.0.7469"
STUB_SERVER="$(dirname "$0")/../../stub-server"

if [ -d "datomic-pro-${VERSION}" ]; then
    echo "Datomic Pro ${VERSION} already present"
    exit 0
fi

# Prefer the copy from stub-server to avoid a second download.
# Must be a real copy (not a symlink) — Docker build context doesn't follow symlinks.
if [ -d "${STUB_SERVER}/datomic-pro-${VERSION}" ]; then
    echo "Copying from stub-server..."
    cp -r "${STUB_SERVER}/datomic-pro-${VERSION}" "datomic-pro-${VERSION}"
    echo "Done"
    exit 0
fi

echo "Downloading Datomic Pro ${VERSION}..."
curl "https://datomic-pro-downloads.s3.amazonaws.com/${VERSION}/datomic-pro-${VERSION}.zip" -O

if ! file "datomic-pro-${VERSION}.zip" | grep -q "Zip archive"; then
    echo "Downloaded file is not a valid zip archive"
    cat "datomic-pro-${VERSION}.zip"
    rm "datomic-pro-${VERSION}.zip"
    exit 1
fi

echo "Extracting..."
unzip -q "datomic-pro-${VERSION}.zip"
rm "datomic-pro-${VERSION}.zip"
echo "Datomic Pro ${VERSION} ready"
