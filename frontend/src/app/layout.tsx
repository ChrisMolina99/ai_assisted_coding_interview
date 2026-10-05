import "~/styles/globals.css";

import { Providers } from "./providers";

export const metadata = {
  title: "Business transfer demo",
  description: "Send instant USD transfers between demo business accounts",
  icons: [{ rel: "icon", url: "/favicon.ico" }],
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className="font-sans">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
