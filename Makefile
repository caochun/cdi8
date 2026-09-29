.PHONY: test java-test simulator-test control-test control-package operator legacy-operator device-gateway device-gateway-local simulator-demo

PYTHON ?= python3
# Source-tree development works without installing the simulator package.
SIM_PYTHON = PYTHONPATH="$(CURDIR)/simulator$(if $(PYTHONPATH),:$(PYTHONPATH))" $(PYTHON)

test: java-test simulator-test

java-test:
	mvn test

simulator-test:
	$(SIM_PYTHON) -m unittest discover -s simulator/gxlf_sim_system/tests -t simulator -v

control-test: java-test

control-package:
	mvn package

operator:
	mvn spring-boot:run

legacy-operator:
	$(SIM_PYTHON) -m gxlf_sim_system.operator_server

device-gateway:
	$(SIM_PYTHON) -m gxlf_sim_system.tango.gateway --mode tango

device-gateway-local:
	$(SIM_PYTHON) -m gxlf_sim_system.tango.gateway --mode local

simulator-demo:
	$(SIM_PYTHON) -m gxlf_sim_system
