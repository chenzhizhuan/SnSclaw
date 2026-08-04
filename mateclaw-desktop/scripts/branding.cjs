/**
 * scripts/branding.cjs — Vite plugin for build-time white-label branding.
 *
 * Reads brand settings from branding.config.json (or BRAND_* env overrides)
 * and replaces hardcoded "SnSclaw" strings in all built files — source code
 * stays untouched.
 *
 * Supported env overrides:
 *   BRAND_NAME, BRAND_TAGLINE, BRAND_TEAM, BRAND_PUBLISHER,
 *   BRAND_COPYRIGHT, BRAND_APP_ID, BRAND_GITHUB_URL, BRAND_LOGO_FILE,
 *   BRAND_HOMEPAGE_URL, BRAND_ABOUT_URL, BRAND_SUPPORT_URL,
 *   BRAND_UPDATE_URL, BRAND_README_URL, BRAND_COMMENTS
 */
'use strict'

const fs = require('fs')
const path = require('path')

/**
 * Load brand configuration from branding.config.json with environment overrides.
 * @param {string} rootDir - Root directory containing branding.config.json.
 * @returns {object} Resolved brand config object with all fields populated.
 */
function loadBrandConfig(rootDir) {
  const configPath = path.join(rootDir, 'branding.config.json')
  let config = {}
  if (fs.existsSync(configPath)) {
    config = JSON.parse(fs.readFileSync(configPath, 'utf-8'))
  }

  // Env vars override the config file.
  const env = process.env
  const defaultGh = 'https://github.com/sns/SnSclaw'
  const defaultHome = 'https://www.sns.app'
  return {
    name:           env.BRAND_NAME        || config.name        || 'SnSclaw',
    tagline:        env.BRAND_TAGLINE     || config.tagline     || 'AI Personal Assistant',
    team:           env.BRAND_TEAM        || config.team        || 'SnSclaw',
    publisher:      env.BRAND_PUBLISHER   || config.publisher   || 'SnSclaw',
    copyright:      env.BRAND_COPYRIGHT   || config.copyright   || 'Copyright © 2026 SnSclaw',
    appId:          env.BRAND_APP_ID      || config.appId       || 'app.sns.claw',
    githubUrl:      env.BRAND_GITHUB_URL  || config.githubUrl   || defaultGh,
    logoFile:       env.BRAND_LOGO_FILE   || config.logoFile    || 'mateclaw_logo_s.png',
    homepageUrl:    env.BRAND_HOMEPAGE_URL || config.homepageUrl || defaultHome,
    aboutUrl:       env.BRAND_ABOUT_URL   || config.aboutUrl    || (defaultHome + '/docs/intro'),
    supportUrl:     env.BRAND_SUPPORT_URL || config.supportUrl  || (defaultGh + '/issues'),
    updateUrl:      env.BRAND_UPDATE_URL  || config.updateUrl   || (defaultHome + '/releases'),
    readmeUrl:      env.BRAND_README_URL  || config.readmeUrl   || (defaultHome + '/docs/desktop'),
    comments:       env.BRAND_COMMENTS    || config.comments    || 'AI Personal Assistant Desktop Workbench',
  }
}

/**
 * Build the string-replacement table.
 *
 * Order matters: longer/more-specific patterns are replaced first to avoid
 * partial matches (e.g. "SnSclaw" before "SnSclaw").
 */
function buildReplacements(brand) {
  const replacements = []

  // 1. Copyright line (most specific)
  replacements.push([
    'Copyright © 2026 SnSclaw',
    brand.copyright,
  ])

  // 2. Team name
  replacements.push(['SnSclaw', brand.team])

  // 3. GitHub URLs
  replacements.push([
    'https://github.com/sns/SnSclaw/issues',
    brand.githubUrl + '/issues',
  ])
  replacements.push([
    'https://github.com/sns/SnSclaw',
    brand.githubUrl,
  ])

  // 4. Logo file path
  replacements.push([
    'mateclaw_logo_s.png',
    brand.logoFile,
  ])

  // 5. Tagline
  replacements.push([
    'AI Personal Assistant',
    brand.tagline,
  ])

  // 6. Split-span brand name in App.vue template:
  //    <span class="mate">SnS</span><span class="claw">claw</span>
  //    Replace the inner text so styling classes are preserved but the text
  //    changes. We split the brand name: first half gets "mate" class, second
  //    half gets "claw" class. If it's a single word, it all goes in "mate".
  var half = Math.ceil(brand.name.length / 2)
  var firstPart = brand.name.slice(0, half)
  var secondPart = brand.name.slice(half)
  replacements.push([
    '>SnS</span><span class="claw">claw<',
    '>' + firstPart + '</span><span class="claw">' + secondPart + '<',
  ])

  // 7. Brand name (catch-all, must come last)
  //    Only replace the exact word "SnSclaw", not "snsclaw" (lowercase,
  //    which is used in H2 database paths and Spring Boot properties that
  //    are coupled with the server and must NOT change).
  replacements.push(['SnSclaw', brand.name])

  return replacements
}

function applyReplacements(code, replacements) {
  var result = code
  for (var i = 0; i < replacements.length; i++) {
    var from = replacements[i][0]
    var to = replacements[i][1]
    // Use split/join for reliable literal string replacement (no regex
    // escaping issues).
    result = result.split(from).join(to)
  }
  return result
}

/**
 * Vite plugin entry point.
 *
 * Usage in vite.config.ts:
 *   import { brandingPlugin } from './scripts/branding.cjs'
 *   plugins: [brandingPlugin()]
 */
function brandingPlugin(options) {
  options = options || {}
  var rootDir = options.rootDir || process.cwd()
  var brand = loadBrandConfig(rootDir)
  var replacements = buildReplacements(brand)

  var isDefault =
    brand.name === 'SnSclaw' &&
    brand.tagline === 'AI Personal Assistant' &&
    brand.team === 'SnSclaw'

  if (!isDefault) {
    console.log('[branding] White-label build: "' + brand.name + '" (tagline: "' + brand.tagline + '")')
  }

  return {
    name: 'mateclaw-branding',
    enforce: 'pre',

    // Transform JS/TS/Vue source before compilation
    transform: function (code, id) {
      if (id.indexOf('node_modules') !== -1) return null
      // Only process source files that might contain brand strings.
      if (!/\.(ts|js|vue|html|css|cjs|mjs)$/.test(id)) return null
      var result = applyReplacements(code, replacements)
      return result !== code ? { code: result, map: null } : null
    },

    // Transform index.html
    transformIndexHtml: function (html) {
      return applyReplacements(html, replacements)
    },
  }
}

module.exports = { brandingPlugin: brandingPlugin, loadBrandConfig: loadBrandConfig, buildReplacements: buildReplacements }
