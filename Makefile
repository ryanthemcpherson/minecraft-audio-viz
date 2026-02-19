# Makefile for minecraft-audio-viz
#
# Usage:
#   make test        - Run Python tests
#   make lint        - Run ruff linter
#   make build       - Build Minecraft plugin JAR
#   make deploy      - Run full deploy script
#   make test-all    - Lint + test + build
#   make clean       - Remove build artifacts

.PHONY: test lint format build deploy test-all clean install dev-install help \
       site-install site-dev site-build site-lint \
       coordinator-install coordinator-dev coordinator-test coordinator-lint \
       worker-install worker-dev

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
# Site (Next.js)
# ---------------------------------------------------------------------------
site-install: ## Install site dependencies
	cd site && npm ci

site-dev: ## Run site dev server (http://localhost:3000)
	cd site && npm run dev

site-build: ## Build site for production
	cd site && npm run build

site-lint: ## Lint site code
	cd site && npm run lint

# ---------------------------------------------------------------------------
# Coordinator (FastAPI)
# ---------------------------------------------------------------------------
coordinator-install: ## Install coordinator dependencies
	cd coordinator && pip install -e ".[dev]"

coordinator-dev: ## Run coordinator dev server (http://localhost:8090)
	cd coordinator && uvicorn app.main:app --reload --port 8090

coordinator-test: ## Run coordinator tests
	cd coordinator && pytest tests/ -v --tb=short

coordinator-lint: ## Lint coordinator code
	ruff check coordinator/
	ruff format --check coordinator/

# ---------------------------------------------------------------------------
# Worker (Cloudflare)
# ---------------------------------------------------------------------------
worker-install: ## Install worker dependencies
	cd worker && npm install

worker-dev: ## Run worker dev server
	cd worker && npm run dev

# ---------------------------------------------------------------------------
# Combined targets
# ---------------------------------------------------------------------------
test-all: lint test coordinator-lint coordinator-test site-lint build ## Run all lint + tests + build

ci: lint format-check test coordinator-lint coordinator-test site-lint site-build build ## Mirror the CI pipeline locally

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
clean: ## Remove build artifacts
	rm -rf minecraft_plugin/target
	rm -rf dist/ build/ *.egg-info
	rm -rf site/.next site/out
	rm -rf coordinator/*.egg-info
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type f -name '*.pyc' -delete 2>/dev/null || true
