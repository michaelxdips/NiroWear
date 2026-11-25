#!/usr/bin/env node
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");

const VOID_TAGS = new Set([
  "area",
  "base",
  "br",
  "col",
  "embed",
  "hr",
  "img",
  "input",
  "link",
  "meta",
  "param",
  "source",
  "track",
  "wbr",
]);
const FOREIGN_CONTAINERS = new Set(["svg", "math"]);

function preserveNewlines(text) {
  return text.replace(/[^\n]/g, "");
}

function stripNonMarkup(html) {
  return html
    .replace(/<!--[\s\S]*?-->/g, preserveNewlines)
    .replace(/<script\b[^>]*>[\s\S]*?<\/script\s*>/gi, preserveNewlines)
    .replace(/<style\b[^>]*>[\s\S]*?<\/style\s*>/gi, preserveNewlines);
}

function checkFile(filePath) {
  if (!fs.existsSync(filePath)) {
    return { ok: false, errors: [`Missing file: ${filePath}`], tags: 0, unclosed: 0 };
  }
  const raw = fs.readFileSync(filePath, "utf8");
  const clean = stripNonMarkup(raw);
  const tagRegex =
    /<\/([a-zA-Z][a-zA-Z0-9:-]*)\s*>|<([a-zA-Z][a-zA-Z0-9:-]*)(?:\s+(?:"[^"]*"|'[^']*'|[^'">\/]|\/(?!>))*)?\s*(\/?)>/g;

  const stack = [];
  const errors = [];
  let tagCount = 0;

  let match;
  while ((match = tagRegex.exec(clean)) !== null) {
    const line = clean.slice(0, match.index).split("\n").length;
    if (match[1]) {
      const tag = match[1].toLowerCase();
      if (VOID_TAGS.has(tag)) {
        errors.push(`line ${line}: unexpected closing </${tag}> for void element`);
        continue;
      }
      if (stack.length === 0) {
        errors.push(`line ${line}: unexpected closing </${tag}> with empty stack`);
      } else {
        const top = stack[stack.length - 1];
        if (top.tag !== tag) {
          errors.push(
            `line ${line}: mismatched closing </${tag}>, expected </${top.tag}> (opened at line ${top.line})`
          );
          const idx = stack.map((item) => item.tag).lastIndexOf(tag);
          if (idx !== -1) {
            stack.length = idx;
          }
        } else {
          stack.pop();
        }
      }
    } else {
      const tag = match[2].toLowerCase();
      const selfClosing = match[3] === "/";
      const inForeign =
        FOREIGN_CONTAINERS.has(tag) || stack.some((item) => FOREIGN_CONTAINERS.has(item.tag));
      tagCount++;
      if (VOID_TAGS.has(tag)) {
        continue;
      }
      if (selfClosing) {
        if (!inForeign) {
          errors.push(`line ${line}: invalid self-closing <${tag}/> on non-void HTML element`);
          stack.push({ tag, line });
        }
      } else {
        stack.push({ tag, line });
      }
    }
  }

  for (const item of stack) {
    errors.push(`line ${item.line}: unclosed <${item.tag}> at EOF`);
  }

  return { ok: errors.length === 0, errors, tags: tagCount, unclosed: stack.length };
}

function runSelfTest() {
  const fixtures = [
    {
      name: "valid-void",
      expectedOk: true,
      html: '<meta charset="utf-8"><link rel="icon" href="a.svg"><br><hr/><img src="a.png" alt=""/><input type="text">',
    },
    {
      name: "valid-svg-self-closing",
      expectedOk: true,
      html: '<main><svg viewBox="0 0 10 10"><rect width="10" height="10"/><path d="M0 0L10 10"/><circle cx="5" cy="5" r="2"/><g><line x1="0" y1="0" x2="1" y2="1"></line></g><text>466 px</text></svg></main>',
    },
    {
      name: "valid-script-style-comment-trap",
      expectedOk: true,
      html: '<!-- <div><p> --><style>.a::after{content:"<div><span>"}</style><main><p>ok</p></main><script>var s = "<main><div/></main></div>";</script>',
    },
    {
      name: "broken-nonvoid-self-closing",
      expectedOk: false,
      html: "<main><div/></main>",
    },
    {
      name: "broken-nonvoid-self-closing-paired",
      expectedOk: false,
      html: "<main><span/>text</span></main>",
    },
    {
      name: "broken-tag-mismatch",
      expectedOk: false,
      html: "<main><p>text</div></main>",
    },
    {
      name: "broken-stray-close",
      expectedOk: false,
      html: "<main><p>text</p></div></main>",
    },
    {
      name: "broken-unclosed-eof",
      expectedOk: false,
      html: "<main><section><p>hello</section>",
    },
    {
      name: "broken-svg-unclosed-child",
      expectedOk: false,
      html: '<main><svg><g><rect width="1" height="1"/></svg></main>',
    },
    {
      name: "broken-void-close",
      expectedOk: false,
      html: "<main><p>text</br></p></main>",
    },
  ];

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "nirowear-html-balance-"));
  let passed = 0;
  try {
    for (const fixture of fixtures) {
      const fixturePath = path.join(tmpDir, `${fixture.name}.html`);
      fs.writeFileSync(fixturePath, fixture.html, "utf8");
      const result = checkFile(fixturePath);
      if (result.ok === fixture.expectedOk) {
        passed++;
        console.log(
          `SELF_TEST_CASE_OK: ${fixture.name} (expectedOk=${fixture.expectedOk}, ok=${result.ok}, errors=${result.errors.length})`
        );
      } else {
        console.error(
          `SELF_TEST_CASE_FAIL: ${fixture.name} (expectedOk=${fixture.expectedOk}, ok=${result.ok}, errors=${JSON.stringify(result.errors)})`
        );
      }
    }
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }

  if (passed === fixtures.length) {
    console.log(`SELF_TEST_PASS: ${passed}/${fixtures.length}`);
    return 0;
  }
  console.error(`SELF_TEST_FAIL: ${passed}/${fixtures.length}`);
  return 1;
}

function main() {
  const args = process.argv.slice(2);
  if (args.length === 1 && args[0] === "--self-test") {
    process.exit(runSelfTest());
  }
  if (args.length === 0) {
    console.error("Usage: node scripts/check-html-balance.js [--self-test | <html-file> [...]]");
    process.exit(1);
  }
  let allOk = true;
  for (const filePath of args) {
    const result = checkFile(filePath);
    if (!result.ok) {
      allOk = false;
      for (const err of result.errors) {
        console.error(`HTML_BALANCE_FAIL: ${filePath}: ${err}`);
      }
    } else {
      console.log(
        `HTML_BALANCE_OK: ${filePath} (tags=${result.tags}, unclosed=${result.unclosed}, errors=0)`
      );
    }
  }
  process.exit(allOk ? 0 : 1);
}

main();
