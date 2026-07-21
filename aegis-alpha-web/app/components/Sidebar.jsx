"use client";

import React, { useEffect, useState } from "react";
import { navItems } from "../lib/routing";
import { Icon } from "./ui";

export function Sidebar({ path, navigate, _collapsed, _setCollapsed, mobile = false, onNavigate }) {
  const [expandedMenus, setExpandedMenus] = useState(["投资组合", "AI+", "回测"]);

  useEffect(() => {
    if (path.startsWith("/portfolio")) setExpandedMenus((items) => Array.from(new Set([...items, "投资组合"])));
    if (
      path.startsWith("/agent") ||
      path.startsWith("/workflow") ||
      path.startsWith("/report") ||
      path.startsWith("/runs/")
    )
      setExpandedMenus((items) => Array.from(new Set([...items, "AI+"])));
    if (path.startsWith("/backtest")) setExpandedMenus((items) => Array.from(new Set([...items, "回测"])));
    if (path.startsWith("/recommendations")) setExpandedMenus((items) => Array.from(new Set([...items, "研究与推荐"])));
    if (path.startsWith("/governance")) setExpandedMenus((items) => Array.from(new Set([...items, "治理"])));
  }, [path]);

  const toggleMenu = (label) =>
    setExpandedMenus((prev) => (prev.includes(label) ? prev.filter((item) => item !== label) : [...prev, label]));
  const isActive = (target, exact) => {
    if (target === "/workflow/runs" && path.startsWith("/runs/")) return true;
    return exact ? path === target : path === target || path.startsWith(`${target}/`);
  };
  const handleNavigate = (target) => {
    navigate(target);
    onNavigate?.();
  };

  return (
    <aside
      className={`${mobile ? "flex w-[280px]" : "hidden w-[255px] lg:flex"} flex-shrink-0 flex-col border-r border-gray-200 bg-white text-gray-700`}
    >
      <div className="flex h-16 items-center px-4">
        <span className="text-xl font-bold tracking-tight text-black">Aegis Alpha</span>
      </div>
      <nav className="flex-1 overflow-y-auto px-3 py-3">
        {navItems.map((item) => {
          const itemOpen = expandedMenus.includes(item.label);
          const itemActive = item.children
            ? item.children.some((child) => isActive(child.path, child.exact))
            : isActive(item.path, item.exact);
          return (
            <div key={item.label} className="mb-1">
              {item.children ? (
                <div>
                  <button
                    onClick={() => toggleMenu(item.label)}
                    style={itemActive ? { backgroundColor: "#171717", color: "#ffffff" } : undefined}
                    className={`flex h-9 w-full items-center rounded-lg px-3 text-sm transition-colors ${itemActive ? "" : "text-gray-700 hover:bg-gray-100"}`}
                  >
                    <span className="flex-shrink-0">
                      <Icon name={item.icon} className="h-4 w-4" />
                    </span>
                    <span className="ml-3 flex-1 text-left">{item.label}</span>
                    <span className={`text-base leading-none transition-transform ${itemOpen ? "rotate-90" : ""}`}>
                      ›
                    </span>
                  </button>
                  {itemOpen && (
                    <div className="mt-2 space-y-1 pl-6">
                      {item.children.map((child) => (
                        <button
                          key={child.path}
                          onClick={() => handleNavigate(child.path)}
                          style={
                            isActive(child.path, child.exact)
                              ? { backgroundColor: "#171717", color: "#ffffff" }
                              : undefined
                          }
                          className={`block h-8 w-full rounded-lg px-3 text-left text-sm transition-colors ${isActive(child.path, child.exact) ? "" : "text-gray-600 hover:bg-gray-100 hover:text-gray-900"}`}
                        >
                          {child.label}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              ) : (
                <button
                  onClick={() => handleNavigate(item.path)}
                  style={itemActive ? { backgroundColor: "#171717", color: "#ffffff" } : undefined}
                  className={`flex h-9 w-full items-center rounded-lg px-3 text-sm transition-colors ${itemActive ? "" : "text-gray-700 hover:bg-gray-100"}`}
                >
                  <span className="flex-shrink-0">
                    <Icon name={item.icon} className="h-4 w-4" />
                  </span>
                  <span className="ml-3">{item.label}</span>
                </button>
              )}
            </div>
          );
        })}
      </nav>
    </aside>
  );
}
