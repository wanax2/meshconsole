#!/bin/sh
# Downloads the Meshtastic protobuf definitions the build generates code from.
set -e
cd "$(dirname "$0")"
if [ -d protobufs/.git ]; then
  git -C protobufs pull --ff-only
else
  git clone --depth 1 https://github.com/meshtastic/protobufs.git protobufs
fi
echo "OK – protobufs/ is ready. Now run: gradle run"
