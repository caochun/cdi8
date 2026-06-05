"use client";

export function LoadingScreen() {
  return (
    <div
      className="fixed inset-0 z-50 flex flex-col items-center justify-center"
      style={{ background: "#08080e" }}
    >
      <div className="relative mb-8">
        {/* Pulsing rings */}
        <div
          className="w-20 h-20 rounded-full border border-blue-500/30 animate-ping"
          style={{ animationDuration: "2s" }}
        />
        <div className="absolute inset-0 flex items-center justify-center">
          <div className="w-10 h-10 rounded-full border-2 border-blue-400/60 border-t-transparent animate-spin" />
        </div>
      </div>
      <p className="text-white/40 text-sm tracking-widest uppercase">
        Loading Facility
      </p>
      <p className="text-white/20 text-xs mt-2">
        激光惯性约束聚变实验设施
      </p>
    </div>
  );
}
