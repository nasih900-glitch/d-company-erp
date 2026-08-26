# Download page

Static release-status page for D Company ERP. It has no build step or framework
and can be hosted from any approved HTTPS static host.

## Deploy

**Cloudflare Pages / Netlify / GitHub Pages**: point the project at `download/`.
If the host applies a Content Security Policy, allow `https://api.github.com` in
`connect-src` so the Android release check can run.

**Manual S3 + CloudFront**:
```bash
aws s3 sync download/ s3://get.dcompany.cloud/ --delete
aws cloudfront create-invalidation --distribution-id ABC --paths '/*'
```

## Publishing releases

The page queries the official `nasih900-glitch/d-company-erp` latest-release API
at runtime. It offers an APK only when the release is public and the filename and
download host match the signed release workflow. Do not enter version numbers or
download URLs by hand.

## Customising

- Keep the live web URL and official repository URL current.
- Keep `den-emblem-gold.png` and `favicon.ico` beside `index.html`.
- Do not add unofficial mirrors, placeholder store links, or unsigned artifacts.
