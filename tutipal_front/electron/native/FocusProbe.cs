using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

// PT-90: once a second, the lowercase exe basename of the foreground window's process, as one JSON line:
// {"exe":"chrome.exe"} or {"exe":null}. Reads only the process image name (no window text, no other process data).
// Exits when a write to stdout fails (the app closed the pipe or ended). Writes go through WriteFile on the raw
// handle because Console.Out silently ignores a broken pipe.
class FocusProbe {
    const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern bool QueryFullProcessImageNameW(IntPtr process, uint flags, StringBuilder name, ref uint size);
    const int STD_OUTPUT_HANDLE = -11;
    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr GetStdHandle(int which);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool WriteFile(IntPtr file, byte[] buffer, uint count, out uint written, IntPtr overlapped);

    static string ForegroundExe() {
        IntPtr hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero) return null;
        uint pid;
        GetWindowThreadProcessId(hwnd, out pid);
        if (pid == 0) return null;
        IntPtr process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
        if (process == IntPtr.Zero) return null;
        try {
            StringBuilder name = new StringBuilder(1024);
            uint size = (uint)name.Capacity;
            if (!QueryFullProcessImageNameW(process, 0, name, ref size)) return null;
            string exe = Path.GetFileName(name.ToString()).ToLowerInvariant();
            return Valid(exe) ? exe : null;
        } finally {
            CloseHandle(process);
        }
    }

    // Same rule as the app and the server: ^[a-z0-9._-]{1,64}\.exe$
    static bool Valid(string exe) {
        if (exe == null || !exe.EndsWith(".exe", StringComparison.Ordinal)) return false;
        int stem = exe.Length - 4;
        if (stem < 1 || stem > 64) return false;
        for (int i = 0; i < stem; i++) {
            char c = exe[i];
            bool ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    static int Main(string[] args) {
        if (args.Length != 0) return 2;
        IntPtr output = GetStdHandle(STD_OUTPUT_HANDLE);
        if (output == IntPtr.Zero || output == new IntPtr(-1)) return 1;
        while (true) {
            string exe = null;
            try { exe = ForegroundExe(); } catch (Exception) { exe = null; }
            byte[] line = Encoding.ASCII.GetBytes(exe == null ? "{\"exe\":null}\n" : "{\"exe\":\"" + exe + "\"}\n");
            uint written;
            if (!WriteFile(output, line, (uint)line.Length, out written, IntPtr.Zero) || written != line.Length) {
                return 0;
            }
            Thread.Sleep(1000);
        }
    }
}
