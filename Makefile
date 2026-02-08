# Makefile for minecraft-audio-viz
#
# Usage:
#   make test        - Run Python tests
#   make lint        - Run ruff linter
#   make build       - Build Minecraft plugin JAR
#   make deploy      - Run full deploy script
#   make test-all    - Lint + test + build
#   make clean       - Remove build artifacts

.PHONY: test lint format build deploy test-all clean install dev-install help

# ---------------------------------------------------------------------------
# Default target
# ---------------------------------------------------------------------------
help: ## Show this help
	@echo "minecraft-audio-viz development commands:"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------------------
# Python
# ---------------------------------------------------------------------------
install: ## Install Python package (production)
	pip install -e .

dev-install: ## Install Python package with dev dependencies
	pip install -e ".[dev]"

test: ## Run Python tests
	pytest audio_processor/tests/ -v --tb=short

lint: ## Run ruff linter
	ruff check audio_processor/ python_client/

format: ## Auto-format Python code with ruff
	ruff format audio_processor/ python_client/

format-check: ## Check Python formatting without changes
	ruff format --check audio_processor/ python_client/

# ---------------------------------------------------------------------------
# Java (Minecraft Plugin)
# ---------------------------------------------------------------------------
build: ## Build Minecraft plugin JAR (skip tests)
	cd minecraft_plugin && mvn clean package -DskipTests

build-verbose: ## Build Minecraft plugin JAR with full output
	cd minecraft_plugin && mvn clean package -DskipTests

# ---------------------------------------------------------------------------
# Deploy
# ---------------------------------------------------------------------------
deploy: ## Run deploy script (pull, build, copy, restart)
	./deploy.sh

deploy-quick: ## Deploy without building (use existing JAR)
	./deploy.sh --skip-build --skip-tests

deploy-dry: ## Dry-run deploy (show what would happen)
	./deploy.sh --dry-run

# ---------------------------------------------------------------------------
# Docker (VJ Server)
# ---------------------------------------------------------------------------
docker-build: ## Build VJ server Docker image
	docker build -t audioviz-vj .

docker-up: ## Start VJ server with docker-compose
	docker-compose up -d

docker-down: ## Stop VJ server
	docker-compose down

docker-logs: ## Tail VJ server logs
	docker-compose logs -f --tail=50

# ---------------------------------------------------------------------------
# Combined targets
# ---------------------------------------------------------------------------
test-all: lint test build ## Run lint + tests + build (full CI locally)

ci: lint format-check test build ## Mirror the CI pipeline locally

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
clean: ## Remove build artifacts
	rm -rf minecraft_plugin/target
	rm -rf dist/ build/ *.egg-info
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type f -name '*.pyc' -delete 2>/dev/null || true
