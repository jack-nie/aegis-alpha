"use client";

import React, { useEffect, useMemo, useRef } from "react";

export function Icon({ name, className = "w-5 h-5" }) {
  const paths = {
    home: "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6",
    portfolio:
      "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z",
    bolt: "M13 10V3L4 14h7v7l9-11h-7z",
    backtest:
      "M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01",
    database:
      "M4 7v10c0 2.21 3.582 4 8 4s8-1.79 8-4V7M4 7c0 2.21 3.582 4 8 4s8-1.79 8-4M4 7c0-2.21 3.582-4 8-4s8 1.79 8 4",
    star: "M11.049 2.927c.3-.921 1.603-.921 1.902 0l1.286 3.955a1 1 0 00.95.69h4.159c.969 0 1.371 1.24.588 1.81l-3.364 2.444a1 1 0 00-.364 1.118l1.285 3.955c.3.921-.755 1.688-1.539 1.118l-3.364-2.444a1 1 0 00-1.176 0l-3.364 2.444c-.784.57-1.838-.197-1.539-1.118l1.285-3.955a1 1 0 00-.364-1.118L4.066 9.382c-.783-.57-.38-1.81.588-1.81h4.159a1 1 0 00.95-.69l1.286-3.955z",
    market: "M4 19h16M6 17V9m4 8V5m4 12v-7m4 7V3",
    audit: "M12 3l7 4v5c0 4.4-2.9 7.4-7 9-4.1-1.6-7-4.6-7-9V7l7-4zm0 5v5m0 4h.01",
    user: "M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z",
    send: "M12 19l9 2-9-18-9 18 9-2zm0 0v-8",
    close: "M6 18L18 6M6 6l12 12",
    arrowRight: "M13 7l5 5m0 0l-5 5m5-5H6",
  };
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={paths[name]} />
    </svg>
  );
}

export function WorkflowPreviewGlyph({ className = "h-4 w-4" }) {
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 18 18">
      <path
        d="M6.8 5.1 4.4 7.3m6.8-2.2 2.3 2.2M6.6 12.8 4.4 10.7m6.9 2.1 2.2-2.1M7.2 4.5h3.6M7.2 13.5h3.6"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="1.35"
      />
      <circle cx="4" cy="9" r="2" strokeWidth="1.35" />
      <circle cx="6" cy="3.8" r="1.8" strokeWidth="1.35" />
      <circle cx="12" cy="3.8" r="1.8" strokeWidth="1.35" />
      <circle cx="6" cy="14.2" r="1.8" strokeWidth="1.35" />
      <circle cx="12" cy="14.2" r="1.8" strokeWidth="1.35" />
    </svg>
  );
}

export function ChatBubbleIcon({ className = "h-5 w-5" }) {
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24">
      <path
        d="M7.5 18.5 4 20l1-3.5A7.7 7.7 0 0 1 4 12.7C4 8.4 7.8 5 12.5 5S21 8.4 21 12.7s-3.8 7.7-8.5 7.7a9.1 9.1 0 0 1-5-1.9Z"
        strokeLinecap="round"
        strokeLinejoin="round"
        strokeWidth="1.8"
      />
    </svg>
  );
}

export function useFocusTrap(open, onClose) {
  const containerRef = useRef(null);
  const returnFocusRef = useRef(null);
  const onCloseRef = useRef(onClose);

  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open || typeof document === "undefined") return undefined;
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const container = containerRef.current;
    const focusableSelector = [
      "a[href]",
      "button:not([disabled])",
      "textarea:not([disabled])",
      "input:not([disabled])",
      "select:not([disabled])",
      "[tabindex]:not([tabindex='-1'])",
    ].join(",");
    const focusFirst = () => {
      const target = container?.querySelector(focusableSelector) || container;
      target?.focus?.();
    };
    const frame = window.requestAnimationFrame(focusFirst);
    const onKeyDown = (event) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onCloseRef.current?.();
        return;
      }
      if (event.key !== "Tab" || !container) return;
      const focusable = Array.from(container.querySelectorAll(focusableSelector)).filter(
        (item) => item.offsetParent !== null || item === document.activeElement,
      );
      if (!focusable.length) {
        event.preventDefault();
        container.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener("keydown", onKeyDown);
      returnFocusRef.current?.focus?.();
    };
  }, [open]);

  return containerRef;
}

export function ModalShell({ title, onClose, children, widthClass = "max-w-lg" }) {
  const titleId = useMemo(() => `modal-${Math.random().toString(36).slice(2)}`, []);
  const containerRef = useFocusTrap(true, onClose);
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center px-4 py-6">
      <div className="absolute inset-0 bg-gray-900/35" onClick={onClose} />
      <section
        ref={containerRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`relative max-h-[calc(100vh-48px)] w-full overflow-auto rounded-lg bg-white shadow-xl ${widthClass}`}
      >
        <div className="flex items-start justify-between gap-4 border-b border-gray-100 px-6 py-4">
          <h3 id={titleId} className="text-base font-semibold text-gray-900">
            {title}
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="grid h-9 w-9 place-items-center rounded-lg text-xl leading-none text-gray-500 hover:bg-gray-100 hover:text-gray-900"
            aria-label={`关闭${title}`}
          >
            ×
          </button>
        </div>
        {children}
      </section>
    </div>
  );
}

export function DrawerShell({ title, onClose, children, widthClass = "max-w-3xl", topClass = "inset-y-0" }) {
  const titleId = useMemo(() => `drawer-${Math.random().toString(36).slice(2)}`, []);
  const containerRef = useFocusTrap(true, onClose);
  return (
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0 bg-white/60 backdrop-blur-[3px]" onClick={onClose} />
      <aside
        ref={containerRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`absolute ${topClass} right-0 flex w-full ${widthClass} flex-col border-l border-gray-100 bg-white shadow-2xl`}
      >
        <div className="flex items-start justify-between gap-4 border-b border-gray-100 px-5 py-4">
          <h3 id={titleId} className="text-base font-semibold text-gray-900">
            {title}
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="grid h-9 w-9 place-items-center rounded-lg text-2xl leading-none text-gray-700 hover:bg-gray-100"
            aria-label={`关闭${title}`}
          >
            ×
          </button>
        </div>
        {children}
      </aside>
    </div>
  );
}

export function SummaryCard({ label, value }) {
  return (
    <div className="card">
      <p className="text-xs text-gray-500">{label}</p>
      <p className="mt-2 text-xl font-bold text-gray-900">{value}</p>
    </div>
  );
}

export function MetricTile({ label, value }) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white px-4 py-3">
      <p className="text-xs font-medium text-gray-500">{label}</p>
      <p className="mt-2 text-2xl font-semibold text-gray-950">{value}</p>
    </div>
  );
}

export function StatusPill({ status }) {
  const text = String(status || "UNKNOWN").toUpperCase();
  const tone = text.includes("DEGRADED")
    ? "border-amber-300 bg-amber-50 text-amber-800"
    : text.includes("INSUFFICIENT")
      ? "border-orange-300 bg-orange-50 text-orange-800"
      : text.includes("FAIL") || text.includes("ERROR") || text.includes("REJECT")
        ? "border-red-200 bg-red-50 text-red-700"
        : text.includes("RUN") || text.includes("PENDING")
          ? "border-blue-200 bg-blue-50 text-blue-700"
          : text.includes("COMPLETE") || text.includes("SUCCESS") || text.includes("APPROVED")
            ? "border-emerald-200 bg-emerald-50 text-emerald-700"
            : "border-gray-200 bg-gray-50 text-gray-600";
  return (
    <span className={`inline-flex rounded-full border px-2 py-0.5 text-[11px] font-semibold ${tone}`}>{text}</span>
  );
}

export function JsonViewer({ value, compact = false, className = "" }) {
  return (
    <pre
      className={`${className} overflow-auto rounded-lg bg-slate-950 p-3 text-xs leading-5 text-slate-100 ${compact ? "mt-3 max-h-56" : ""}`}
    >
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}
export function Panel({ title, children }) {
  return (
    <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-4">
      <h4 className="text-sm font-semibold text-slate-900">{title}</h4>
      {children}
    </section>
  );
}
export function Field({ label, children }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-sm font-medium text-slate-700">{label}</span>
      {children}
    </label>
  );
}
export function ReadOnlyField({ label, value }) {
  return (
    <div>
      <span className="mb-1.5 block text-sm font-medium text-slate-700">{label}</span>
      <div className="truncate rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-sm text-slate-700">
        {value}
      </div>
    </div>
  );
}
export function Button({ children, onClick, variant = "default" }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        variant === "primary"
          ? "rounded-lg bg-slate-950 px-4 py-2 text-sm font-semibold text-white hover:bg-slate-800"
          : "rounded-lg border border-slate-200 bg-white px-4 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50"
      }
    >
      {children}
    </button>
  );
}
export function Badge({ children, tone = "default" }) {
  const styles = {
    default: "bg-slate-100 text-slate-600",
    warning: "border border-amber-200 bg-amber-50 text-amber-700",
    dark: "bg-slate-900 text-white",
    blue: "bg-blue-100 text-blue-700",
  };
  return (
    <span
      className={`inline-flex rounded-full px-2 py-0.5 text-[11px] font-semibold ${styles[tone] || styles.default}`}
    >
      {children}
    </span>
  );
}
