"""
Keep a child process running, and make sure it dies when this supervisor does.

Why this exists at all: Task Scheduler's own restart policy was found
unreliable in testing, so the coordinator and worker are each launched by a
supervisor that restarts them itself.

Why it is a real file rather than a generated one: the previous version was
emitted as a PowerShell string array by the install scripts, which made
anything beyond a few lines impractical to write or review — and the fix below
needs more than a few lines. Both installers now register this instead, so
coordinator and worker share one implementation.

THE BUG THIS FIXES
------------------
The old supervisor did `subprocess.Popen(...)` and nothing tied the child's
lifetime to its own. Stopping the scheduled task killed only the supervisor.
The orphaned child kept running and kept the port, so:

  * every later "restart" started a child that could not bind, exited, and got
    restarted five seconds later, forever;
  * the machine went on serving whatever code the orphan had loaded;
  * every health check passed, because a week-old process answers HTTP exactly
    like a fresh one.

Observed on the live coordinator: one process served for seven days across two
"successful" restarts, still holding a FAISS index built before rows had been
deleted from the database.

On Windows the child is therefore assigned to a Job Object created with
JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE. When this process exits — including being
hard-killed by Task Scheduler, where no cleanup code of ours could run — the
kernel closes the job handle and terminates the child with it. That is the only
mechanism that survives a hard kill; try/finally and atexit do not.

Elsewhere the child is put in its own process group and signal handlers
terminate it, which covers ordinary service-manager stops.

Usage:
    pythonw supervise.py --python <interpreter> --script main.py \
        --cwd <repo> --log <path> [--label coordinator] [--restart-delay 5]
"""

from __future__ import annotations

import argparse
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

IS_WINDOWS = sys.platform == "win32"
CREATE_NO_WINDOW = 0x08000000


# ── Windows job object ───────────────────────────────────────────────────────

def _make_kill_on_close_job():
    """
    A Job Object whose closure kills every process in it, or None if that
    can't be set up. Returning None is deliberate: failing to get a job is a
    reason to run with the old, weaker behaviour, never a reason to refuse to
    start the service.
    """
    import ctypes
    from ctypes import wintypes

    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000
    JobObjectExtendedLimitInformation = 9

    class BasicLimits(ctypes.Structure):
        _fields_ = [
            ("PerProcessUserTimeLimit", ctypes.c_int64),
            ("PerJobUserTimeLimit", ctypes.c_int64),
            ("LimitFlags", wintypes.DWORD),
            ("MinimumWorkingSetSize", ctypes.c_size_t),
            ("MaximumWorkingSetSize", ctypes.c_size_t),
            ("ActiveProcessLimit", wintypes.DWORD),
            ("Affinity", ctypes.c_size_t),
            ("PriorityClass", wintypes.DWORD),
            ("SchedulingClass", wintypes.DWORD),
        ]

    class IoCounters(ctypes.Structure):
        _fields_ = [
            (name, ctypes.c_uint64)
            for name in (
                "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                "ReadTransferCount", "WriteTransferCount", "OtherTransferCount",
            )
        ]

    class ExtendedLimits(ctypes.Structure):
        _fields_ = [
            ("BasicLimitInformation", BasicLimits),
            ("IoInfo", IoCounters),
            ("ProcessMemoryLimit", ctypes.c_size_t),
            ("JobMemoryLimit", ctypes.c_size_t),
            ("PeakProcessMemoryUsed", ctypes.c_size_t),
            ("PeakJobMemoryUsed", ctypes.c_size_t),
        ]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateJobObjectW.restype = wintypes.HANDLE
    job = kernel32.CreateJobObjectW(None, None)
    if not job:
        return None

    info = ExtendedLimits()
    info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    ok = kernel32.SetInformationJobObject(
        job, JobObjectExtendedLimitInformation, ctypes.byref(info), ctypes.sizeof(info)
    )
    if not ok:
        kernel32.CloseHandle(job)
        return None
    return job


def _assign_to_job(job, proc) -> bool:
    import ctypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    return bool(kernel32.AssignProcessToJobObject(job, int(proc._handle)))


# ── Supervision ──────────────────────────────────────────────────────────────

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python", required=True, help="Interpreter to run the script with")
    parser.add_argument("--script", required=True, help="Script to run, relative to --cwd")
    parser.add_argument("--cwd", required=True, help="Working directory")
    parser.add_argument("--log", required=True, help="File to append stdout/stderr to")
    parser.add_argument("--label", default="service", help="Name used in log lines")
    parser.add_argument("--restart-delay", type=float, default=5.0)
    parser.add_argument(
        "--env", action="append", default=[], metavar="KEY=VALUE",
        help="Environment variable for the child; repeatable. The worker needs "
             "USERPROFILE (so panns_inference finds ~/panns_data) and COORDINATOR_URL.",
    )
    parser.add_argument(
        "--stdout-to-log", action=argparse.BooleanOptionalAction, default=True,
        help="Send the child's stdout to the log. The worker sets --no-stdout-to-log: "
             "its progress output is large and its useful output goes to stderr.",
    )
    args = parser.parse_args()

    child_env = os.environ.copy()
    for pair in args.env:
        key, sep, value = pair.partition("=")
        if not sep:
            parser.error(f"--env expects KEY=VALUE, got {pair!r}")
        child_env[key] = value

    log_path = Path(args.log)
    log_path.parent.mkdir(parents=True, exist_ok=True)

    job = None
    if IS_WINDOWS:
        try:
            job = _make_kill_on_close_job()
        except Exception:
            job = None

    child: subprocess.Popen | None = None

    def stop(signum, _frame):
        # Ordinary, polite stop. On Windows the job object is what actually
        # guarantees cleanup; this path just makes a graceful stop graceful.
        if child and child.poll() is None:
            try:
                child.terminate()
            except Exception:
                pass
        sys.exit(0)

    for sig in (signal.SIGTERM, signal.SIGINT):
        try:
            signal.signal(sig, stop)
        except (ValueError, OSError):
            pass  # not available in this context; the job object still covers us

    while True:
        with open(log_path, "a", buffering=1, encoding="utf-8") as log:
            log.write(f"--- {args.label} starting ---\n")
            if IS_WINDOWS and job is None:
                log.write(
                    f"--- WARNING: {args.label} has no job object; a hard stop of this "
                    "supervisor will orphan the child and it will keep holding its port ---\n"
                )
            popen_kwargs = {
                "cwd": args.cwd,
                "stdout": log if args.stdout_to_log else subprocess.DEVNULL,
                "stderr": log,
                "stdin": subprocess.DEVNULL,
                "env": child_env,
            }
            if IS_WINDOWS:
                popen_kwargs["creationflags"] = CREATE_NO_WINDOW
            else:
                popen_kwargs["start_new_session"] = True

            child = subprocess.Popen([args.python, args.script], **popen_kwargs)

            if job is not None and not _assign_to_job(job, child):
                log.write(
                    f"--- WARNING: could not assign {args.label} to the job object; "
                    "it may survive this supervisor ---\n"
                )

            code = child.wait()
            log.write(
                f"--- {args.label} exited (code {code}); "
                f"restarting in {args.restart_delay:g}s ---\n"
            )
        time.sleep(args.restart_delay)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
