using System;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Threading;

// Read-only geometry for exactly one HWND selected through desktopCapturer.
class WindowTarget {
    [StructLayout(LayoutKind.Sequential)] struct Rect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] struct Point { public int X, Y; }
    [DllImport("user32.dll")] static extern bool IsWindow(IntPtr h);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr h);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out Rect r);
    [DllImport("user32.dll")] static extern bool GetClientRect(IntPtr h, out Rect r);
    [DllImport("user32.dll")] static extern bool ClientToScreen(IntPtr h, ref Point p);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr h, uint flags);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr value);
    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    [DllImport("dwmapi.dll")] static extern int DwmGetWindowAttribute(IntPtr h, int attr, out Rect value, int size);
    [DllImport("dwmapi.dll", EntryPoint="DwmGetWindowAttribute")] static extern int DwmInt(IntPtr h, int attr, out int value, int size);
    static string Json(Rect r) {
        return String.Format(CultureInfo.InvariantCulture,"{{\"x\":{0},\"y\":{1},\"width\":{2},\"height\":{3}}}",r.Left,r.Top,r.Right-r.Left,r.Bottom-r.Top);
    }
    static int Main(string[] args) {
        long id;
        if (args.Length != 1 || !Int64.TryParse(args[0], out id) || id <= 0) return 2;
        try { SetProcessDpiAwarenessContext(new IntPtr(-4)); } catch (EntryPointNotFoundException) { SetProcessDPIAware(); }
        IntPtr hwnd = new IntPtr(id);
        uint originalPid;
        GetWindowThreadProcessId(hwnd, out originalPid);
        if (originalPid == 0) return 3;
        while (true) {
            uint pid;
            GetWindowThreadProcessId(hwnd, out pid);
            if (!IsWindow(hwnd) || pid != originalPid) { Console.WriteLine("{\"state\":\"gone\"}"); return 0; }
            int cloaked;
            DwmInt(hwnd, 14, out cloaked, 4);
            if (!IsWindowVisible(hwnd) || IsIconic(hwnd) || cloaked != 0) { Console.WriteLine("{\"state\":\"hidden\"}"); return 0; }
            Rect window, frame, client;
            if (!GetWindowRect(hwnd, out window)) return 4;
            if (DwmGetWindowAttribute(hwnd, 9, out frame, 16) != 0) frame = window;
            if (!GetClientRect(hwnd, out client)) client = window;
            else {
                Point origin = new Point();
                if (!ClientToScreen(hwnd, ref origin)) return 4;
                client.Right += origin.X; client.Bottom += origin.Y; client.Left = origin.X; client.Top = origin.Y;
            }
            bool foreground = GetAncestor(GetForegroundWindow(), 3) == GetAncestor(hwnd, 3);
            Console.WriteLine("{\"state\":\"ok\",\"foreground\":" + (foreground?"true":"false") + ",\"window\":"+Json(window)+",\"frame\":"+Json(frame)+",\"client\":"+Json(client)+"}");
            Console.Out.Flush();
            Thread.Sleep(200);
        }
    }
}
