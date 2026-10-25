.PHONY: java-device-package control-package operator tango-bridge workbench workbench-start workbench-stop workbench-restart workbench-status java-workbench-smoke

control-package:
	mvn package

operator:
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

# Builds both JARs, then starts isolated Java-only services for end-to-end verification.
java-workbench-smoke:
	mvn package
	mvn -pl server -Dexec.skip=true package

# Compatibility aliases for the Java-only end-to-end suite.
.PHONY: workbench-smoke gxlf-smoke
workbench-smoke gxlf-smoke: java-workbench-smoke

java-device-package:
	mvn -pl device-simulator package
