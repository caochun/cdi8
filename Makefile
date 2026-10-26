.PHONY: java-device-package control-package operator tango-bridge workbench workbench-start workbench-stop workbench-restart workbench-status java-workbench-smoke

control-package:
	mvn package

operator:
	mvn -pl model-contract -am install -DskipTests
	mvn -pl server spring-boot:run

# Managed Java bridge; devices are created from saved SCXML versions in the workbench.
tango-bridge:
	java -jar device-simulator/target/device-simulator-0.1.0.jar --bridge

workbench:
	mvn package
	java scripts/Workbench.java start

workbench-start:
	java scripts/Workbench.java start

workbench-stop:
	java scripts/Workbench.java stop

workbench-restart:
	mvn package
	java scripts/Workbench.java restart

workbench-status:
	java scripts/Workbench.java status

# Real HTTP / Flowable / JTango / Java SCXML integration tests in isolated ports and data directories.
java-workbench-smoke:
	mvn -Pnew-chain-e2e verify

java-device-package:
	mvn -pl device-simulator -am package
