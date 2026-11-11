import type { Metadata } from "next";
import "./globals.css";
import { AuthProvider } from "@/context/AuthContext";
import { Navbar } from "@/components/Navbar";

export const metadata: Metadata = {
  title: "GateKeeper — Event Management & Ticketing",
  description: "Scalable event management, ticketing, QR issuance, and door check-in platform",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body className="min-h-screen bg-slate-50 text-slate-900 antialiased">
        <AuthProvider>
          <Navbar />
          <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:py-8">{children}</main>
        </AuthProvider>
      </body>
    </html>
  );
}

