"use client";

import React, { useState } from "react";
import { request } from "../../lib/request";

export function LoginPage({ onLogin }) {
  const [username, setUsername] = useState("guanghui.nie");
  const [password, setPassword] = useState("guanghui.nie");
  const [error, setError] = useState("");
  const submit = async (event) => {
    event.preventDefault();
    setError("");
    try {
      const result = await request("/auth/login", { method: "POST", body: JSON.stringify({ username, password }) });
      onLogin(result.access_token, { username });
    } catch (ex) {
      setError(ex.message);
    }
  };
  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
      <form onSubmit={submit} className="w-full max-w-md rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
        <h1 className="text-xl font-bold text-gray-900">Aegis Alpha</h1>
        <p className="mt-1 text-sm text-gray-500">使用 Aegis Alpha 账号进入平台。</p>
        <div className="mt-6 space-y-4">
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-gray-700">用户名</span>
            <input
              className="input-field"
              name="username"
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-gray-700">密码</span>
            <input
              className="input-field"
              name="password"
              autoComplete="current-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </label>
        </div>
        {error && <p className="mt-3 text-sm text-red-600">{error}</p>}
        <button className="btn-primary mt-6 w-full justify-center" type="submit">
          登录
        </button>
      </form>
    </div>
  );
}
