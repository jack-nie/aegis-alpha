# Aegis Alpha 本地开发快捷方式，全部转发到 ./aegis.sh（Windows 请用 .\aegis.ps1 或 aegis.cmd）。
# 例: make up / make up SERVICE=backend / make logs SERVICE=web / make down
SERVICE ?= all
ARGS ?=

.PHONY: help up down restart status logs smoke setup infra-up infra-down

help:
	@./aegis.sh help

up:
	@./aegis.sh up $(SERVICE) $(ARGS)

down:
	@./aegis.sh down $(SERVICE) $(ARGS)

restart:
	@./aegis.sh restart $(SERVICE) $(ARGS)

status:
	@./aegis.sh status $(SERVICE)

logs:
	@./aegis.sh logs $(SERVICE) $(ARGS)

smoke:
	@./aegis.sh smoke

setup:
	@./aegis.sh setup $(SERVICE)

infra-up:
	@./aegis.sh infra up

infra-down:
	@./aegis.sh infra down
