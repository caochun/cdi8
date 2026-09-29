.PHONY: test operator

PYTHON ?= python3

test:
	$(PYTHON) -m unittest discover -s gxlf_sim_system/tests -t . -v

operator:
	$(PYTHON) -m gxlf_sim_system.operator_server
