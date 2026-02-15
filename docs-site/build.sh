#!/bin/bash
# Build the MCAV documentation site
# Output: docs-site/site/

set -e

cd "$(dirname "$0")"

if ! command -v mkdocs &> /dev/null; then
    echo "Installing dependencies..."
    pip install -r requirements.txt
fi

echo "Building documentation..."
mkdocs build

echo "Done. Output at docs-site/site/"
