import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "ICF 激光聚变设施 — 实验流程可视化",
  description: "激光惯性约束聚变实验全流程交互式3D演示",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="zh-CN">
      <body className="w-screen h-screen overflow-hidden">{children}</body>
    </html>
  );
}
