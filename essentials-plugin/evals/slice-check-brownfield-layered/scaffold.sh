#!/usr/bin/env bash
exec bash "$(dirname "${BASH_SOURCE[0]}")/../_lib/stage.sh" tests/fixtures/brownfield-layered
