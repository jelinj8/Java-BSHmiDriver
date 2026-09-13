#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -cp "$DIR/bshmidriver-cli.jar:$DIR/lib/*" cz.bliksoft.hmieink.Cli "$@"
