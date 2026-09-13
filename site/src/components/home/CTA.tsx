import Button, { ArrowIcon } from "@/components/ui/Button";
import { LINKS } from "@/lib/links";

export default function CTA() {
  return (
    <section className="relative px-6 pb-28 sm:pb-32">
      <div className="mx-auto max-w-7xl">
        <div className="relative overflow-hidden rounded-3xl border border-white/10 bg-bg-secondary px-8 py-16 text-center sm:px-16 sm:py-20">
          <div
            className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_top,rgba(0,204,255,0.14)_0%,rgba(91,106,255,0.08)_40%,transparent_70%)]"
            aria-hidden="true"
          />
          <div className="pointer-events-none absolute inset-0 grid-backdrop opacity-60" aria-hidden="true" />

          <div className="relative">
            <h2 className="font-heading text-3xl font-bold leading-tight sm:text-4xl md:text-5xl">
              Ready to light up your server?
            </h2>
            <p className="mx-auto mt-4 max-w-xl text-base text-text-secondary sm:text-lg">
              Follow the setup guide, join the Discord to trade patterns, or star the repo if you
              want to follow the release.
            </p>
            <div className="mt-9 flex flex-col items-center justify-center gap-3 sm:flex-row">
              <Button href="/getting-started" size="lg" className="group">
                Get started
                <ArrowIcon className="transition-transform group-hover:translate-x-1" />
              </Button>
              <Button href={LINKS.discord} external variant="secondary" size="lg">
                Join the Discord
              </Button>
              <Button href={LINKS.github} external variant="ghost" size="lg">
                Star on GitHub
              </Button>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
