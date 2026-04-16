#!/bin/sh

# Minimal Maven wrapper helper.
if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
else
  echo "Maven nincs telepitve a rendszerre. Telepitsd a Mavent, vagy csereld le ezt a fajlt teljes Maven wrapperre." >&2
  exit 1
fi
