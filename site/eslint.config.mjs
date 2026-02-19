import nextConfig from "eslint-config-next/core-web-vitals";
import tsConfig from "eslint-config-next/typescript";

const eslintConfig = [
  { ignores: ["scripts/**", "src/lib/patterns/fengari-browser.js", "shims/**"] },
  ...nextConfig,
  ...tsConfig,
];

export default eslintConfig;
