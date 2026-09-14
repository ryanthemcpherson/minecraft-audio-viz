import Button from "@/components/ui/Button";
import { LINKS } from "@/lib/links";

export default function CTA() {
  return (
    <section className="border-t border-white/[0.07] px-6 py-24 sm:py-28">
      <div className="mx-auto flex max-w-7xl flex-col gap-8 lg:flex-row lg:items-end lg:justify-between">
        <div className="max-w-xl">
          <h2 className="font-heading text-[clamp(1.75rem,1.2rem+2vw,2.75rem)] font-bold leading-[1.08] tracking-[-0.02em]">
            Ready to light up your server?
          </h2>
          <p className="mt-4 text-[17px] leading-relaxed text-text-secondary">
            Follow the setup guide, or join the Discord to trade patterns and follow the release.
          </p>
        </div>
        <div className="flex flex-wrap gap-3">
          <Button href="/getting-started" size="lg">
            Get started
          </Button>
          <Button href={LINKS.discord} external variant="secondary" size="lg">
            Join the Discord
          </Button>
          <Button href={LINKS.github} external variant="ghost" size="lg">
            GitHub
          </Button>
        </div>
      </div>
    </section>
  );
}
