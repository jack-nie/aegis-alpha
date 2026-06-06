"""Remote execution helper for deployment."""

import sys
import io
import paramiko

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")

SSH_HOST = "106.53.115.183"
SSH_USER = "root"
SSH_PASS = "KG.cL_bN_G9U7pK"


def run(cmd, print_output=True):
    """Execute a command on the remote server."""
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    ssh.connect(SSH_HOST, username=SSH_USER, password=SSH_PASS, timeout=15)

    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=300)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    exit_code = stdout.channel.recv_exit_status()

    if print_output:
        if out:
            print(out, end="")
        if err:
            print(err, end="", file=sys.stderr)

    ssh.close()
    return exit_code, out, err


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python remote_exec.py <command>")
        sys.exit(1)
    cmd = " ".join(sys.argv[1:])
    exit_code, _, _ = run(cmd)
    sys.exit(exit_code)
