import { Metadata } from "next";
import Footer from "@/components/Footer";

export const metadata: Metadata = {
  title: "Privacy Policy - MCAV",
  description:
    "Learn how MCAV collects, uses, and protects your personal information.",
};

export default function PrivacyPage() {
  return (
    <>
      {/* Hero */}
      <section className="px-6 pt-32 pb-16">
        <div className="mx-auto max-w-4xl text-center">
          <h1 className="text-4xl font-bold tracking-tight sm:text-5xl md:text-6xl">
            Privacy <span className="text-gradient">Policy</span>
          </h1>
          <p className="mt-4 text-lg text-text-secondary sm:text-xl">
            Last updated: February 14, 2026
          </p>
        </div>
      </section>

      {/* Content */}
      <div className="px-6 pb-32">
        <div className="mx-auto max-w-3xl">
          <div className="glass-card rounded-2xl p-8 sm:p-12 space-y-8">

            <section>
              <h2 className="text-2xl font-bold mb-4">Introduction</h2>
              <p className="text-text-secondary leading-relaxed">
                MCAV (Minecraft Audio Visualizer) is an open source project created by Ryan McPherson.
                This privacy policy explains how we collect, use, and protect your personal information
                when you use our website and services.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Information We Collect</h2>

              <h3 className="text-lg font-semibold mb-3 text-electric-blue">Account Information</h3>
              <p className="text-text-secondary leading-relaxed mb-4">
                When you create an account on mcav.live, we collect:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mb-6">
                <li>Discord user ID, username, and avatar (if you sign in with Discord OAuth)</li>
                <li>Email address (if provided by Discord)</li>
                <li>DJ name and server configuration preferences</li>
              </ul>

              <h3 className="text-lg font-semibold mb-3 text-electric-blue">Usage Data</h3>
              <p className="text-text-secondary leading-relaxed mb-4">
                We automatically collect certain information when you visit our website:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mb-6">
                <li>Browser type and version</li>
                <li>IP address and general location</li>
                <li>Pages visited and time spent on the site</li>
                <li>Referring site or source</li>
              </ul>

              <h3 className="text-lg font-semibold mb-3 text-electric-blue">Audio Data</h3>
              <p className="text-text-secondary leading-relaxed">
                When using the DJ Client app, audio is processed locally on your device.
                Only frequency band data (FFT analysis results) is transmitted to VJ servers.
                We do not record, store, or transmit raw audio content.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">How We Use Your Information</h2>
              <p className="text-text-secondary leading-relaxed mb-4">
                We use the collected information to:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary">
                <li>Provide and maintain the MCAV service</li>
                <li>Authenticate users via Discord OAuth</li>
                <li>Enable DJ-to-server connections using connect codes</li>
                <li>Improve and optimize the website and services</li>
                <li>Communicate important updates or security notifications</li>
                <li>Respond to support requests on GitHub Issues</li>
              </ul>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Cookies and Tracking</h2>
              <p className="text-text-secondary leading-relaxed mb-4">
                We use cookies and similar technologies to:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mb-4">
                <li>Maintain your login session</li>
                <li>Remember your preferences</li>
                <li>Analyze site usage and performance</li>
              </ul>
              <p className="text-text-secondary leading-relaxed">
                You can disable cookies in your browser settings, but this may affect
                your ability to use certain features of the site.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Third-Party Services</h2>
              <p className="text-text-secondary leading-relaxed mb-4">
                MCAV integrates with the following third-party services:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mb-4">
                <li><strong>Discord</strong> - For OAuth authentication</li>
                <li><strong>Cloudflare</strong> - For CDN and DDoS protection</li>
                <li><strong>Railway</strong> - For hosting the coordinator API</li>
              </ul>
              <p className="text-text-secondary leading-relaxed">
                These services have their own privacy policies governing how they handle your data.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Data Retention</h2>
              <p className="text-text-secondary leading-relaxed">
                We retain your account information for as long as your account is active.
                You may request deletion of your account and associated data at any time by
                contacting us via{" "}
                <a
                  href="https://github.com/ryanthemcpherson/minecraft-audio-viz/issues"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-electric-blue hover:underline"
                >
                  GitHub Issues
                </a>.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Your Rights (GDPR/CCPA)</h2>
              <p className="text-text-secondary leading-relaxed mb-4">
                Depending on your location, you may have the following rights:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mb-4">
                <li><strong>Access:</strong> Request a copy of the personal data we hold about you</li>
                <li><strong>Correction:</strong> Request corrections to inaccurate data</li>
                <li><strong>Deletion:</strong> Request deletion of your personal data</li>
                <li><strong>Portability:</strong> Request a machine-readable copy of your data</li>
                <li><strong>Objection:</strong> Object to certain types of data processing</li>
              </ul>
              <p className="text-text-secondary leading-relaxed">
                To exercise these rights, please open an issue on our{" "}
                <a
                  href="https://github.com/ryanthemcpherson/minecraft-audio-viz/issues"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-electric-blue hover:underline"
                >
                  GitHub repository
                </a>.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Security</h2>
              <p className="text-text-secondary leading-relaxed">
                We implement industry-standard security measures to protect your data, including:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mt-4 mb-4">
                <li>Encrypted WebSocket connections (WSS) for DJ-to-server communication</li>
                <li>JWT-based authentication with short-lived tokens</li>
                <li>Bcrypt password hashing for VJ operator credentials</li>
                <li>HTTPS for all web traffic</li>
              </ul>
              <p className="text-text-secondary leading-relaxed">
                However, no method of transmission over the internet is 100% secure.
                While we strive to protect your data, we cannot guarantee absolute security.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Children&apos;s Privacy</h2>
              <p className="text-text-secondary leading-relaxed">
                MCAV is not intended for children under the age of 13. We do not knowingly
                collect personal information from children. If you believe we have collected
                information from a child, please contact us immediately.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Changes to This Policy</h2>
              <p className="text-text-secondary leading-relaxed">
                We may update this privacy policy from time to time. Changes will be posted
                on this page with an updated revision date. Continued use of MCAV after changes
                constitutes acceptance of the updated policy.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Contact</h2>
              <p className="text-text-secondary leading-relaxed">
                For questions about this privacy policy or to exercise your rights, please contact us:
              </p>
              <ul className="list-none space-y-2 text-text-secondary mt-4">
                <li>
                  <strong>Project:</strong> MCAV (Minecraft Audio Visualizer)
                </li>
                <li>
                  <strong>Maintainer:</strong> Ryan McPherson
                </li>
                <li>
                  <strong>Support:</strong>{" "}
                  <a
                    href="https://github.com/ryanthemcpherson/minecraft-audio-viz/issues"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-electric-blue hover:underline"
                  >
                    GitHub Issues
                  </a>
                </li>
                <li>
                  <strong>Website:</strong>{" "}
                  <a
                    href="https://mcav.live"
                    className="text-electric-blue hover:underline"
                  >
                    https://mcav.live
                  </a>
                </li>
              </ul>
            </section>

          </div>
        </div>
      </div>

      <Footer />
    </>
  );
}
