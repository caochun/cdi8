.PHONY: test operator control-test control-package device-gateway

PYTHON ?= python3

test:
	$(PYTHON) -m unittest discover -s gxlf_sim_system/tests -t . -v

operator:
	$(PYTHON) -m gxlf_sim_system.operator_server

control-test:
	mvn -f control-server/pom.xml test

control-package:
	mvn -f control-server/pom.xml package

device-gateway:
	$(PYTHON) -m gxlf_sim_system.tango.gateway --mode tango
