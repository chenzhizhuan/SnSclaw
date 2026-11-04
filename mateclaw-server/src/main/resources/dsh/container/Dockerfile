# Pass a reviewed, immutable Node 22 base image (name@sha256:...).
ARG NODE_IMAGE=node@sha256:b21fe589dfbe5cc39365d0544b9be3f1f33f55f3c86c87a76ff65a02f8f5848e
FROM ${NODE_IMAGE}
ARG DSH_VERSION
ARG DSH_LOCK_SHA256
LABEL vip.mateclaw.dsh.version="${DSH_VERSION}" \
      vip.mateclaw.dsh.lock-sha256="${DSH_LOCK_SHA256}"
RUN apt-get update \
    && apt-get install -y --no-install-recommends bubblewrap \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/mateclaw-dsh
COPY locks/${DSH_VERSION}.json package-lock.json
RUN node -e 'const fs=require("fs");const crypto=require("crypto");if(crypto.createHash("sha256").update(fs.readFileSync("package-lock.json")).digest("hex")!==process.env.DSH_LOCK_SHA256)throw Error("lock digest mismatch");const l=JSON.parse(fs.readFileSync("package-lock.json"));fs.writeFileSync("package.json",JSON.stringify({name:"mateclaw-dsh-runtime",version:"1.0.0",private:true,dependencies:l.packages[""].dependencies}))' \
    && npm ci --registry=https://registry.npmjs.org --no-audit --no-fund \
    && npm cache clean --force
ENTRYPOINT ["/opt/mateclaw-dsh/node_modules/.bin/dsh"]
