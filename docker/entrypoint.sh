#!/bin/bash
set -e

# Inject license key into transactor properties if provided
if [ -n "$DATOMIC_LICENSE_KEY" ]; then
    echo "license-key=${DATOMIC_LICENSE_KEY}" >> /opt/datomic/config/transactor.properties
    echo "License key configured"
else
    echo "WARNING: No DATOMIC_LICENSE_KEY provided"
fi

# Transactor JVM heap. bin/transactor reads -Xmx/-Xms ONLY as positional
# args (before the properties file) and ignores the JAVA_OPTS env var; with
# no flag it defaults to 1g, which is too small once the dev DB grows —
# indexing can't complete and it crash-loops on "Indexing retry limit
# exceeded". Pass the flags explicitly. Override via XMX (e.g. XMX=6g).
XMX="${XMX:-4g}"

# Start the transactor
echo "Starting Datomic transactor (heap ${XMX})..."
exec /opt/datomic/bin/transactor \
    "-Xmx${XMX}" "-Xms${XMX}" \
    /opt/datomic/config/transactor.properties
