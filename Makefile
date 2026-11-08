.PHONY: package backend-package adapter-package simulator-package facility workbench simulation simulation-start simulation-stop simulation-restart simulation-status facility-start facility-stop workbench-start workbench-stop workbench-restart facility-observation-test simulation-test

package:
	mvn package

backend-package:
	mvn -pl facility-server,workbench-server -am package

adapter-package:
	mvn -pl tango-adapter -am package

simulator-package:
	mvn -pl device-simulator -am package

# Run each business backend independently in the foreground.
facility:
	mvn -pl facility-server -am package
	java -jar facility-server/target/facility-server-0.1.0.jar

workbench:
	mvn -pl facility-contract -am install -DskipTests
	mvn -pl workbench-server spring-boot:run

# The facility server embeds Tango access and launches the simulation device JVM.
simulation:
	mvn package
	java scripts/Services.java start

simulation-start:
	java scripts/Services.java start

simulation-stop:
	java scripts/Services.java stop

simulation-restart:
	mvn package
	java scripts/Services.java restart

simulation-status:
	java scripts/Services.java status

# Facility scope includes its simulation device child process.
facility-start:
	java scripts/Services.java start facility

facility-stop:
	java scripts/Services.java stop facility

workbench-start:
	java scripts/Services.java start workbench

workbench-stop:
	java scripts/Services.java stop workbench

workbench-restart:
	java scripts/Services.java restart workbench

# Facility-only observation boundary: real simulator JVM, JTango and facility server; no Workbench.
facility-observation-test:
	mvn -Pfacility-e2e -pl facility-server -am -Dexec.skip=true verify

# Real HTTP / Flowable / JTango / SCXML integration, using isolated data and ports.
simulation-test:
	mvn -Pworkbench-e2e verify
