.PHONY: test java-test simulator-test component-demo component-panel control-test control-package operator device-gateway device-gateway-local tango-bridge

PYTHON ?= python3
# Source-tree development works without installing the simulator package.
SIM_PYTHON = PYTHONPATH="$(CURDIR)/simulator$(if $(PYTHONPATH),:$(PYTHONPATH))" $(PYTHON)

test: java-test simulator-test

java-test:
	mvn test

simulator-test:
	$(SIM_PYTHON) -m unittest discover -s simulator/gxlf_sim_system/tests -t simulator -v

component-demo:
	$(SIM_PYTHON) -m gxlf_sim_system --component seed demo

component-panel:
	$(SIM_PYTHON) -m gxlf_sim_system.gui --component seed --demo

control-test: java-test

control-package:
	mvn package

operator:
	mvn spring-boot:run

device-gateway:
	$(SIM_PYTHON) -m gxlf_sim_system.tango.gateway --mode tango

device-gateway-local:
	$(SIM_PYTHON) -m gxlf_sim_system.tango.gateway --mode local

# Four SCXML component types plus explicit demo synchronization/environment device.
tango-bridge:
	$(SIM_PYTHON) -m gxlf_sim_system.tango.control_bridge --demo
