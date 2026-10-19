.PHONY: test java-test simulator-test component-demo control-test control-package operator tango-bridge

PYTHON ?= python3
# Source-tree development works without installing the simulator package.
SIM_PYTHON = PYTHONPATH="$(CURDIR)/simulator$(if $(PYTHONPATH),:$(PYTHONPATH))" $(PYTHON)

test: java-test simulator-test

java-test:
	mvn test

simulator-test:
	$(SIM_PYTHON) -m unittest discover -s simulator/tests -t simulator -v

component-demo:
	$(SIM_PYTHON) -m gxlf_component_simulator --component seed demo

control-test: java-test

control-package:
	mvn package

operator:
	mvn -pl server spring-boot:run

# Four SCXML component types plus explicit demo synchronization/environment device.
tango-bridge:
	$(SIM_PYTHON) -m gxlf_component_simulator.tango.control_bridge --demo

# These targets require isolated, freshly started services. They mutate demo state.
BASE_URL ?= http://127.0.0.1:8080
BRIDGE_URL ?= http://127.0.0.1:8766

.PHONY: workbench-smoke gxlf-smoke
workbench-smoke:
	$(PYTHON) tests/e2e/workbench_smoke.py --base $(BASE_URL) --bridge $(BRIDGE_URL)

gxlf-smoke:
	$(PYTHON) tests/e2e/gxlf_lifecycle_smoke.py --base $(BASE_URL) --bridge $(BRIDGE_URL)
