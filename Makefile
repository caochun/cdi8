.PHONY: test

PYTHON ?= python3

test:
	$(PYTHON) -m unittest discover -s gxlf_sim_system/tests -t . -v
