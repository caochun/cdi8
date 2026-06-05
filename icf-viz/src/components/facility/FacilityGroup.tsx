import { memo } from "react";
import { GXLFPhysicalFacility } from "./GXLFPhysicalFacility";

export const FacilityGroup = memo(function FacilityGroup() {
  return (
    <group>
      <GXLFPhysicalFacility />
    </group>
  );
});
