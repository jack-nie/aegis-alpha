const { spawnSync } = require("child_process");

const env = {
  ...process.env,
  NEXT_PRIVATE_BUILD_WORKER: "0",
};

const result = spawnSync("next", ["build"], {
  stdio: "inherit",
  shell: process.platform === "win32",
  env,
});

process.exit(result.status === null ? 1 : result.status);
