import Hero from "@/components/home/Hero";
import HowItWorks from "@/components/home/HowItWorks";
import LazyPatternStage from "@/components/patterns/LazyPatternStage";
import Features from "@/components/home/Features";
import Audiences from "@/components/home/Audiences";
import Demo from "@/components/home/Demo";
import FAQ from "@/components/home/FAQ";
import CTA from "@/components/home/CTA";
import Footer from "@/components/Footer";

export default function Home() {
  return (
    <>
      <Hero />
      <HowItWorks />
      <LazyPatternStage />
      <Features />
      <Audiences />
      <Demo />
      <FAQ />
      <CTA />
      <Footer />
    </>
  );
}
