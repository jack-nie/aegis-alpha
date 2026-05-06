import "./globals.css";
import "@xyflow/react/dist/style.css";

export const metadata = {
  title: "Aegis Alpha Platform",
  description: "Aegis Alpha investment research orchestration platform",
};

export default function RootLayout({ children }) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
