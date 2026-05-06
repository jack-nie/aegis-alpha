import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const source = readFileSync(join(root, "app", "App.jsx"), "utf8");

assert.match(source, /readInitialPathname/, "App should initialize navigation from window.location.pathname.");
assert.match(source, /window\.history\.pushState/, "App navigation should update the browser URL.");
assert.match(source, /window\.addEventListener\(["']popstate["']/, "App should restore pages on browser back/forward.");
assert.doesNotMatch(source, /const\s+\[path,\s*setPath\]\s*=\s*useState\(["']\/["']\)/, "Path state should not be a fixed in-memory default.");

assert.match(source, /function\s+ModalShell/, "Agent dialogs should use the shared ModalShell.");
assert.match(source, /function\s+DrawerShell/, "Drawers should use the shared DrawerShell.");
assert.match(source, /role=["']dialog["']/, "Dialogs and drawers should expose role=dialog.");
assert.match(source, /aria-modal=["']true["']/, "Dialogs and drawers should expose aria-modal=true.");
assert.match(source, /useFocusTrap/, "Dialogs and drawers should trap focus and restore it on close.");

assert.match(source, /lg:flex/, "Sidebar should be desktop-only at large breakpoints.");
assert.match(source, /mobileMenuOpen/, "App should expose mobile drawer navigation state.");
assert.match(source, /mobileMenuOpen[\s\S]*role=["']dialog["'][\s\S]*aria-modal=["']true["']/, "Mobile navigation drawer should expose modal dialog semantics.");
assert.match(source, /mobileMenuRef\s*=\s*useFocusTrap\(mobileMenuOpen/, "Mobile navigation drawer should trap focus while open.");
assert.match(source, /aria-label=["']打开导航["']/, "Mobile header should expose a named menu button.");
assert.doesNotMatch(source, />语言<\/span>|<option>EN<\/option>/, "Header should not expose a fake language switcher.");

assert.match(source, /"\/portfolio\/assets":\s*"投资组合 \/ 持仓"/, "Portfolio assets title should match the holdings page.");
assert.match(source, /交易流水/, "Portfolio IA should name the trade ledger clearly.");
assert.match(source, /dataCompleteness|sourceStatus/, "Holdings empty states should distinguish missing detail sync from truly empty holdings.");
assert.match(source, /\/portfolio\/\$\{selectedPortfolioId\}\/positions/, "Holdings should consume the backend portfolio positions contract.");
assert.match(source, /setPortfolioContract/, "Portfolio pages should store backend contract completeness instead of only inferring from list counts.");

assert.doesNotMatch(source, /min-w-\[1480px\]/, "Workflow run center should not force a table wider than desktop.");
assert.match(source, /workflow-run-card-list/, "Workflow run center should provide a mobile card layout.");
assert.match(source, /availableActions/, "Workflow run center should consume or derive available actions.");
assert.match(source, /actionReasons/, "Workflow run center should surface disabled action reasons.");

assert.doesNotMatch(source, /图占位|内容占位/, "User-visible placeholder copy should be removed from shipped pages.");
assert.match(source, /formatMarketTimestamp/, "Market data timestamps should be localized.");
assert.match(source, /sanitizeText/, "Market data text should be normalized before display.");

assert.match(source, /autoComplete=["']username["']/, "Login username should expose autocomplete=username.");
assert.match(source, /autoComplete=["']current-password["']/, "Login password should expose autocomplete=current-password.");
assert.match(source, /aria-label=["']发送研究问题["']/, "Home search submit button should have an accessible name.");
assert.match(source, /aria-label=["']打开 AI Copilot["']/, "Floating Copilot button should have an accessible name.");
