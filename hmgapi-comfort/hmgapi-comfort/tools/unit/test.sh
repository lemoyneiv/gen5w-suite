#!/bin/sh
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$(cd "$HERE/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
javac --release 8 -Xlint:-options -d "$T" \
    "$MOD/app/java/dev/lemoyne/comfort/ComfortConfig.java" \
    "$MOD/app/java/dev/lemoyne/comfort/ComfortPolicy.java" \
    "$HERE/ComfortPolicyTest.java"
java -cp "$T" dev.lemoyne.comfort.ComfortPolicyTest
