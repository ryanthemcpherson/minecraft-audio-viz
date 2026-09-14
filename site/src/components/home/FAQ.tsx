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
    a: `Paper ${RELEASE.minecraft} on Java ${RELEASE.java}, on Linux (x86_64 or ARM64) or Windows (x86_64), plus one extra TCP port for the control center and DJ connections. The supported baseline is 4 vCPU, 8 GiB of memory, and 2 GiB of free disk.`,
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
    a: "Drop a Lua file into the patterns folder. It exports a name, a description, and a calculate function that returns block positions for the current audio state. Patterns are discovered automatically.",
  },
  {
    q: "Is it free?",
    a: "Yes. MCAV is open source under the MIT license.",
  },
];

/** Seven questions do not need accordions. */
export default function FAQ() {
  return (
    <section id="faq" className="border-t border-white/[0.07] px-6 py-24 sm:py-28">
      <div className="mx-auto grid max-w-7xl gap-12 lg:grid-cols-[minmax(0,4fr)_minmax(0,8fr)]">
        <SectionHeader
          title="Questions people ask first."
          lede={
            <>
              Something missing? Ask in the{" "}
              <a href={LINKS.discord} target="_blank" rel="noopener noreferrer" className="text-disc-cyan hover:underline">
                Discord
              </a>{" "}
              or open a{" "}
              <a href={LINKS.discussions} target="_blank" rel="noopener noreferrer" className="text-disc-cyan hover:underline">
                discussion on GitHub
              </a>
              .
            </>
          }
        />

        <dl className="grid gap-x-12 gap-y-8 sm:grid-cols-2">
          {faqs.map((item) => (
            <div key={item.q}>
              <dt className="font-heading text-lg font-bold leading-snug">{item.q}</dt>
              <dd className="mt-2 text-[15px] leading-relaxed text-text-secondary">{item.a}</dd>
            </div>
          ))}
        </dl>
      </div>
    </section>
  );
}
