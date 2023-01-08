# ========================================================
# makefile
# ========================================================
default: help	# default target

# Sample keycloak
# docker run -p 8080:8080 -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin quay.io/keycloak/keycloak:20.0.2 start-dev

# --------------------------------------------------------
# Include master project makefile
# --------------------------------------------------------

# Includes custom vars
-include .env

# -----------------------------------------------------------------------------
# Internals variables
# -----------------------------------------------------------------------------

# commands
CMD_DOCKER=docker
CMD_DOCKER_COMPOSE=docker compose -f docker-compose.yml
CMD_DOCKER_COMPOSE_LOCAL=${CMD_DOCKER_COMPOSE} -f docker-compose.local.yml

# --------------------------------------------------------
# Define project docker image

# ----
KEYCLOAK_SERVICE_NAME := keycloak
KEYCLOAK_SERVICE_SHELL := sh
# ----
POSTGRES_SERVICE_NAME := postgres
POSTGRES_SERVICE_SHELL := sh
# ----

export
# ========================================================
# Targets
# ========================================================

# --------------------------------------------------------
##@ Project task
# --------------------------------------------------------

build: ## Build docker image from source
	${CMD_DOCKER_COMPOSE} build

run: build ## Start the container
	${CMD_DOCKER_COMPOSE} up -d

logs: ## Display logs from the running container
	${CMD_DOCKER_COMPOSE} logs -f

stop: ## Stop the container
	${CMD_DOCKER_COMPOSE} stop

rm: stop ## Destroy the container
	${CMD_DOCKER_COMPOSE} down

# --------------------------------------------------------
##@ Internal services starting tasks
# --------------------------------------------------------

dev: ## Start 'keycloak' service in dev mode (themes are not cached so that you can easily work on them without a need to restart)
	${CMD_DOCKER_COMPOSE_LOCAL} up -d ${KEYCLOAK_SERVICE_NAME}

kc: kc-rm ## (Re)start 'keycloak' service only (also available: 'kc-[build|logs|sh|stop|rm])
	${CMD_DOCKER_COMPOSE} up -d ${KEYCLOAK_SERVICE_NAME}
kc-build:; ${CMD_DOCKER_COMPOSE} build $(KEYCLOAK_SERVICE_NAME)
kc-logs:; ${CMD_DOCKER_COMPOSE} logs -f $(KEYCLOAK_SERVICE_NAME)
kc-sh:; ${CMD_DOCKER_COMPOSE} exec $(KEYCLOAK_SERVICE_NAME) $(KEYCLOAK_SERVICE_SHELL)
kc-stop:; ${CMD_DOCKER_COMPOSE} stop $(KEYCLOAK_SERVICE_NAME)
kc-rm: kc-stop; ${CMD_DOCKER_COMPOSE} rm -f $(KEYCLOAK_SERVICE_NAME)


db: db-rm ## (Re)start 'postgres' service only (also available: 'db-[build|logs|sh|stop|rm])
	${CMD_DOCKER_COMPOSE} up -d ${POSTGRES_SERVICE_NAME}
db-build:; ${CMD_DOCKER_COMPOSE} build $(POSTGRES_SERVICE_NAME)
db-logs:; ${CMD_DOCKER_COMPOSE} logs -f $(POSTGRES_SERVICE_NAME)
db-sh:; ${CMD_DOCKER_COMPOSE} exec $(POSTGRES_SERVICE_NAME) $(POSTGRES_SERVICE_SHELL)
db-stop:; ${CMD_DOCKER_COMPOSE} stop $(POSTGRES_SERVICE_NAME)
db-rm: db-stop; ${CMD_DOCKER_COMPOSE} rm -f $(POSTGRES_SERVICE_NAME)

# --------------------------------------------------------
##@ Commons basics tasks
# --------------------------------------------------------

# source: https://stackoverflow.com/questions/2214575/passing-arguments-to-make-run
bash: ## Open a new bash session
	bash

# source: https://suva.sh/posts/well-documented-makefiles/
help:  ## Display this help
	@awk 'BEGIN {FS = ":.*##"; printf "\nUsage:\n  make \033[36m<task>\033[0m\n"} /^[0-9a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2 } /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) } ' $(MAKEFILE_LIST)
