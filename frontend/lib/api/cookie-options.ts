// Local Docker uses HTTP on loopback. HTTPS deployments keep secure cookies by default.
export function authCookiesSecure() {
  return process.env.AUTH_COOKIE_SECURE === "false"
    ? false
    : process.env.NODE_ENV === "production";
}
