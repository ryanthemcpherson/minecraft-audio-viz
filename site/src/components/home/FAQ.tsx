import SectionHeader from "@/components/ui/SectionHeader";
import { RELEASE } from "@/lib/releaseStatus";
import { LINKS } from "@/lib/links";

const faqs = [
  {
    q: "Do players need to install anything?",
    a: `No. Everything renders server-side with Display Entities. Any vanilla Java client that can join a Paper ${RELEASE.minecraft} server sees the show.`,
  },
  {
    q: "What does the server need?",
    a: `Paper ${RELEASE.minecraft} on Java ${RELEASE.java}, running on Linux (x86_64 or ARM64) or Windows (x86_64), plus one extra TCP port for the control center and DJ connections. The supported baseline is 4 vCPU, 8 GiB of memory, and 2 GiB of free disk.`,
  },
  {
    q: "Does the DJ need Windows?",
    a: "Yes, for now. Audio capture uses WASAPI process loopback, which is Windows 10 and 11 only. The server itself can run on Linux.",
  },
  {
    q: "Is my audio streamed to the server?",
    a: "Not for visualization. The DJ client analyzes audio locally and sends compact band, beat, and tempo frames over an encrypted WebSocket. Raw audio only leaves the machine if you opt into the separate voice feature for Simple Voice Chat.",
  },
  {
    q: "Can more than one DJ play?",
    a: "Yes. The VJ server keeps a queue of connected DJs and the operator decides who is live and when transitions happen.",
  },
  {
    q: "How do I write my own pattern?",
    a: "Drop a Lua file into the patterns folder. It exports a name, a description, and a calculate function that returns entity positions for the current audio state. Patterns are discovered automatically.",
  },
  {
    q: "Is it free?",
    a: "Yes. MCAV is open source under the MIT license.",
  },
];

export default function FAQ() {
  return (
    <section id="faq" className="relative px-6 py-28 sm:py-32">
      <div className="mx-auto grid max-w-7xl gap-12 lg:grid-cols-[1fr_1.6fr]">
        <div>
          <SectionHeader
            eyebrow="FAQ"
            title="Questions people ask first."
            lede={
              <>
                Something missing? Ask in the{" "}
                <a
                  href={LINKS.discord}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-disc-cyan hover:underline"
                >
                  Discord
                </a>{" "}
                or open a{" "}
                <a
                  href={LINKS.discussions}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="text-disc-cyan hover:underline"
                >
                  discussion on GitHub
                </a>
                .
              </>
            }
          />
        </div>

        <div className="flex flex-col gap-3">
          {faqs.map((item) => (
            <details key={item.q} className="faq-item flat-card group rounded-xl">
              <summary className="flex items-center justify-between gap-4 px-6 py-5 text-left font-semibold">
                <span>{item.q}</span>
                <svg
                  className="faq-chevron h-4 w-4 shrink-0 text-text-secondary transition-transform"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  <polyline points="6 9 12 15 18 9" />
                </svg>
              </summary>
              <div className="px-6 pb-5 text-sm leading-relaxed text-text-secondary">{item.a}</div>
            </details>
          ))}
        </div>
      </div>
    </section>
  );
}
