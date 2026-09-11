import { Metadata } from "next";

export const metadata: Metadata = {
  title: "Log in",
  description: "Log in to your MCAV account to manage servers, DJ sessions, and visualization settings.",
  robots: { index: false, follow: false },
};

export default function LoginLayout({ children }: { children: React.ReactNode }) {
  return children;
}
