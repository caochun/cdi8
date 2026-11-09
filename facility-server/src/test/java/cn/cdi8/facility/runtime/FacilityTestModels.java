package cn.cdi8.facility.runtime;

/** Explicitly limited contracts for transport/topology tests; never shipped as facility defaults. */
public final class FacilityTestModels {
    private FacilityTestModels() {}
    static String definition() throws java.io.IOException {return java.nio.file.Files.readString(java.nio.file.Path.of("examples/gxlf/system/gxlf-facility.system-definition.xml"));}
    static String projection() throws java.io.IOException {return java.nio.file.Files.readString(java.nio.file.Path.of("examples/gxlf/system/gxlf-facility-state-projection.xml"));}
    public static String opticalOnly(String definition) {
        return definition.replaceAll("(?s)\\s*<operatingConstraints>.*?</operatingConstraints>","")
            .replaceAll("(?s)\\s*<operationAdmissions>.*?</operationAdmissions>","")
            .replaceAll("\\s*<factRequirement\\b[^>]*/>","")
            .replaceAll("<requirements>\\s*</requirements>","");
    }
}
