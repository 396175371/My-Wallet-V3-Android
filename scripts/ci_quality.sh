#!/usr/bin/env bash

./gradlew lintEnvProdDebug -Dpre-dex=false -Pkotlin.incremental=false --no-daemon --stacktrace

# ktlint not enabled for now - waiting for some fixes in the next version