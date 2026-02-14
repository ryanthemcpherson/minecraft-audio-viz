import { Metadata } from "next";
import Footer from "@/components/Footer";

export const metadata: Metadata = {
  title: "Terms of Service - MCAV",
  description:
    "Read the terms and conditions for using MCAV services.",
};

export default function TermsPage() {
  return (
    <>
      {/* Hero */}
      <section className="px-6 pt-32 pb-16">
        <div className="mx-auto max-w-4xl text-center">
          <h1 className="text-4xl font-bold tracking-tight sm:text-5xl md:text-6xl">
            Terms of <span className="text-gradient">Service</span>
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
              <h2 className="text-2xl font-bold mb-4">Acceptance of Terms</h2>
              <p className="text-text-secondary leading-relaxed">
                By accessing or using MCAV (Minecraft Audio Visualizer) services, including the
                website at mcav.live, the DJ Client app, VJ server software, and Minecraft plugin,
                you agree to be bound by these Terms of Service. If you do not agree to these terms,
                do not use our services.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Description of Service</h2>
              <p className="text-text-secondary leading-relaxed mb-4">
                MCAV is an open source real-time audio visualization system for Minecraft. The service includes:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary">
                <li>A Minecraft plugin for rendering 3D audio visualizations using Display Entities</li>
                <li>A VJ server for processing audio data and managing visualization patterns</li>
                <li>A DJ Client desktop app for capturing and streaming audio</li>
                <li>A web-based control panel and 3D preview tool</li>
                <li>A coordinator API for multi-DJ show management</li>
              </ul>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Open Source License</h2>
              <p className="text-text-secondary leading-relaxed mb-4">
                MCAV is released under the{" "}
                <a
                  href="https://opensource.org/licenses/MIT"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-electric-blue hover:underline"
                >
                  MIT License
                </a>. You are free to use, modify, and distribute the software in accordance
                with the terms of the MIT License. The full license text is available in the{" "}
                <a
                  href="https://github.com/ryanthemcpherson/minecraft-audio-viz/blob/main/LICENSE"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-electric-blue hover:underline"
                >
                  project repository
                </a>.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">User Accounts</h2>
              <p className="text-text-secondary leading-relaxed mb-4">
                To access certain features, you may need to create an account:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mb-4">
                <li>You must provide accurate and complete information when creating an account</li>
                <li>You are responsible for maintaining the security of your account credentials</li>
                <li>You must not share your account with others or use another person&apos;s account</li>
                <li>You are responsible for all activity that occurs under your account</li>
                <li>You must notify us immediately of any unauthorized access to your account</li>
              </ul>
              <p className="text-text-secondary leading-relaxed">
                We reserve the right to suspend or terminate accounts that violate these terms.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Acceptable Use</h2>
              <p className="text-text-secondary leading-relaxed mb-4">
                When using MCAV, you agree not to:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary">
                <li>Use the service for any illegal or unauthorized purpose</li>
                <li>Violate any laws in your jurisdiction (including copyright laws)</li>
                <li>Transmit malware, viruses, or malicious code</li>
                <li>Attempt to gain unauthorized access to our systems or user accounts</li>
                <li>Interfere with or disrupt the service or servers</li>
                <li>Use the service to spam, harass, or harm others</li>
                <li>Impersonate any person or entity</li>
                <li>Scrape or harvest data from the service without permission</li>
                <li>Reverse engineer or attempt to extract source code (except as permitted by the MIT License)</li>
              </ul>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Audio Content</h2>
              <p className="text-text-secondary leading-relaxed">
                You are solely responsible for ensuring you have the rights to stream and visualize
                any audio content you use with MCAV. This includes music, voice chat, or any other audio.
                We do not monitor or control the audio content streamed through the service and assume
                no liability for copyright infringement or licensing violations.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Server Hosting</h2>
              <p className="text-text-secondary leading-relaxed">
                If you host a VJ server or Minecraft server using MCAV:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mt-4">
                <li>You are responsible for the content and conduct on your server</li>
                <li>You must comply with all applicable laws and Minecraft&apos;s terms of service</li>
                <li>You are responsible for securing your server and managing user access</li>
                <li>You must not use MCAV infrastructure (mcav.live) to facilitate illegal activity</li>
              </ul>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Termination</h2>
              <p className="text-text-secondary leading-relaxed">
                We reserve the right to suspend or terminate your access to MCAV services at any time,
                with or without cause, with or without notice. Reasons for termination may include:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mt-4 mb-4">
                <li>Violation of these Terms of Service</li>
                <li>Fraudulent, abusive, or illegal activity</li>
                <li>Prolonged inactivity</li>
                <li>Technical or security reasons</li>
              </ul>
              <p className="text-text-secondary leading-relaxed">
                You may terminate your account at any time by contacting us via{" "}
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
              <h2 className="text-2xl font-bold mb-4">Disclaimer of Warranties</h2>
              <p className="text-text-secondary leading-relaxed mb-4">
                MCAV is provided &quot;as is&quot; and &quot;as available&quot; without warranties of any kind,
                either express or implied, including but not limited to:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mb-4">
                <li>Warranties of merchantability or fitness for a particular purpose</li>
                <li>Warranties of non-infringement</li>
                <li>Warranties that the service will be uninterrupted, secure, or error-free</li>
                <li>Warranties regarding the accuracy or reliability of results</li>
              </ul>
              <p className="text-text-secondary leading-relaxed">
                You use MCAV at your own risk. We do not guarantee that the software will meet your
                requirements or that it will be compatible with your system.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Limitation of Liability</h2>
              <p className="text-text-secondary leading-relaxed mb-4">
                To the maximum extent permitted by law, MCAV, its maintainers, contributors, and affiliates
                shall not be liable for any indirect, incidental, special, consequential, or punitive damages,
                including but not limited to:
              </p>
              <ul className="list-disc pl-6 space-y-2 text-text-secondary mb-4">
                <li>Loss of profits, data, use, goodwill, or other intangible losses</li>
                <li>Unauthorized access to or alteration of your transmissions or data</li>
                <li>Statements or conduct of any third party on the service</li>
                <li>Service interruptions or errors</li>
                <li>Damage to your computer system or loss of data</li>
              </ul>
              <p className="text-text-secondary leading-relaxed">
                This limitation applies even if we have been advised of the possibility of such damages.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Indemnification</h2>
              <p className="text-text-secondary leading-relaxed">
                You agree to indemnify, defend, and hold harmless MCAV, its maintainers, contributors,
                and affiliates from any claims, liabilities, damages, losses, and expenses (including
                legal fees) arising out of or related to your use of the service, violation of these terms,
                or infringement of any third-party rights.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Third-Party Services</h2>
              <p className="text-text-secondary leading-relaxed">
                MCAV integrates with third-party services including Discord, Cloudflare, and Railway.
                Your use of these services is subject to their respective terms of service and privacy policies.
                We are not responsible for the availability, content, or practices of third-party services.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Modifications to Terms</h2>
              <p className="text-text-secondary leading-relaxed">
                We reserve the right to modify these Terms of Service at any time. Changes will be posted
                on this page with an updated revision date. Continued use of MCAV after changes constitutes
                acceptance of the updated terms. Material changes will be communicated via the project&apos;s{" "}
                <a
                  href="https://github.com/ryanthemcpherson/minecraft-audio-viz"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-electric-blue hover:underline"
                >
                  GitHub repository
                </a>.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Governing Law</h2>
              <p className="text-text-secondary leading-relaxed">
                These Terms of Service shall be governed by and construed in accordance with the laws
                of the jurisdiction in which the project maintainer resides, without regard to conflict
                of law principles.
              </p>
            </section>

            <section>
              <h2 className="text-2xl font-bold mb-4">Contact</h2>
              <p className="text-text-secondary leading-relaxed">
                For questions about these Terms of Service, please contact us:
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
