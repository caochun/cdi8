package cn.cdi8.workbench.facility;

/** Explicitly limited contracts for transport/topology tests; never shipped as facility defaults. */
public final class FacilityTestModels {
    private FacilityTestModels() {}
    public static String opticalOnly(String definition) {
        return definition.replaceAll("(?s)\\s*<operatingConstraints>.*?</operatingConstraints>","")
            .replaceAll("\\s*<factRequirement\\b[^>]*/>","")
            .replaceAll("<requirements>\\s*</requirements>","");
    }
}
