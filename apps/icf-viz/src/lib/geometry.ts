import * as THREE from "three";

/**
 * Generate positions on a sphere using golden angle distribution.
 * Points converge toward the origin from `radius` distance.
 */
export function sphericalBeamPositions(
  count: number,
  radius: number
): { position: THREE.Vector3; direction: THREE.Vector3 }[] {
  const results: { position: THREE.Vector3; direction: THREE.Vector3 }[] = [];
  const goldenAngle = Math.PI * (3 - Math.sqrt(5));

  for (let i = 0; i < count; i++) {
    const y = 1 - (i / (count - 1)) * 2;
    const r = Math.sqrt(1 - y * y);
    const theta = goldenAngle * i;
    const dir = new THREE.Vector3(
      Math.cos(theta) * r,
      y,
      Math.sin(theta) * r
    );
    const pos = dir.clone().multiplyScalar(radius);
    results.push({ position: pos, direction: dir.clone().negate() });
  }
  return results;
}

/**
 * Build a straight path from outer point to inner point (toward origin).
 */
export function beamLineCurve(
  outerPos: THREE.Vector3,
  innerRadius: number
): THREE.LineCurve3 {
  const dir = outerPos.clone().normalize();
  const inner = dir.multiplyScalar(innerRadius);
  return new THREE.LineCurve3(outerPos, inner);
}

/**
 * Create a ring of positions on the XZ plane.
 */
export function circlePositions(
  count: number,
  radius: number,
  y: number = 0,
  startAngle: number = 0
): THREE.Vector3[] {
  const positions: THREE.Vector3[] = [];
  for (let i = 0; i < count; i++) {
    const angle = startAngle + (i / count) * Math.PI * 2;
    positions.push(
      new THREE.Vector3(Math.cos(angle) * radius, y, Math.sin(angle) * radius)
    );
  }
  return positions;
}
