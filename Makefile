.PHONY: dev stop test test-static test-engine test-scenarios sim-validate sim-run sim-bridge viz-install viz-dev viz-event

SIM_MODULE := gxlf_sim_system
VIZ_DIR := apps/icf-viz
BRIDGE_PORT ?= 8765
VIZ_PORT ?= 3001
VIZ_FALLBACK_PORT ?= 3000

dev:
	./scripts/run-event-viz.sh

stop:
	@for port in $(BRIDGE_PORT) $(VIZ_PORT) $(VIZ_FALLBACK_PORT); do \
		pids="$$(lsof -ti tcp:$$port 2>/dev/null || true)"; \
		if [ -n "$$pids" ]; then \
			echo "Stopping process(es) on port $$port: $$pids"; \
			kill $$pids 2>/dev/null || true; \
		else \
			echo "No process on port $$port"; \
		fi; \
	done

test: test-static test-engine test-bridge test-scenarios

test-static:
	python3 -m unittest gxlf_sim_system.tests.test_static_model_validation

test-engine:
	python3 -m unittest gxlf_sim_system.tests.test_engine_semantics

test-bridge:
	python3 -m unittest gxlf_sim_system.tests.test_event_bridge_faults

test-scenarios:
	python3 -m unittest gxlf_sim_system.tests.test_model_scenarios

sim-validate:
	python3 -m $(SIM_MODULE) validate

sim-run:
	python3 -m $(SIM_MODULE) run

sim-bridge:
	python3 -m $(SIM_MODULE) bridge --host 127.0.0.1 --port $(BRIDGE_PORT)

viz-install:
	cd $(VIZ_DIR) && npm install

viz-dev:
	cd $(VIZ_DIR) && npm run dev

viz-event:
	cd $(VIZ_DIR) && NEXT_PUBLIC_GXLF_EVENTS_URL=http://127.0.0.1:$(BRIDGE_PORT)/events npm run dev
