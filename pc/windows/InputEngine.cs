using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Threading;

namespace CompassLink
{
    public sealed class InputEngine
    {
        private const uint Keyboard = 1;
        private const uint KeyUp = 2;
        private const uint ExtendedKey = 1;
        private const uint Move = 0x0001;
        private const uint Wheel = 0x0800;
        private const int LeaseMs = 3000;
        private static readonly UIntPtr EventTag = new UIntPtr(0x434C4E4B);
        private static readonly int InputSize = Marshal.SizeOf(typeof(Input));
        private static readonly Dictionary<string, ushort> Keys = new Dictionary<string, ushort>
        {
            { "BACKSPACE", 0x08 }, { "TAB", 0x09 }, { "ENTER", 0x0D },
            { "SHIFT", 0x10 }, { "CTRL", 0x11 }, { "ALT", 0x12 }, { "ESCAPE", 0x1B },
            { "SPACE", 0x20 }, { "PAGEUP", 0x21 }, { "PAGEDOWN", 0x22 },
            { "END", 0x23 }, { "HOME", 0x24 }, { "LEFT", 0x25 }, { "UP", 0x26 },
            { "RIGHT", 0x27 }, { "DOWN", 0x28 }, { "INSERT", 0x2D }, { "DELETE", 0x2E },
            { "SEMICOLON", 0xBA }, { "EQUALS", 0xBB }, { "COMMA", 0xBC },
            { "MINUS", 0xBD }, { "PERIOD", 0xBE }, { "SLASH", 0xBF }, { "BACKTICK", 0xC0 },
            { "LEFTBRACKET", 0xDB }, { "BACKSLASH", 0xDC }, { "RIGHTBRACKET", 0xDD },
            { "QUOTE", 0xDE }
        };
        private static readonly Dictionary<string, uint> MouseButtons = new Dictionary<string, uint>
        {
            { "LEFT", 0x0002 }, { "RIGHT", 0x0008 }, { "MIDDLE", 0x0020 }
        };

        private readonly object gate = new object();
        private readonly HashSet<ushort> held = new HashSet<ushort>();
        private readonly HashSet<uint> heldMouse = new HashSet<uint>();
        private readonly Stopwatch clock = Stopwatch.StartNew();
        private long deadline;
        private bool faulted;

        public static int Main()
        {
            return new InputEngine().Serve();
        }

        private int Serve()
        {
            try
            {
                using (var watchdog = new Timer(CheckLease, null, 200, 200))
                {
                    Console.WriteLine("ready");
                    string line;
                    while ((line = Console.ReadLine()) != null && line != "quit")
                    {
                        lock (gate)
                        {
                            if (faulted) break;
                            Apply(line);
                            if (faulted) break;
                        }
                    }
                }
            }
            catch (Exception error)
            {
                lock (gate) Fault(error.Message);
            }
            finally
            {
                // EOF also covers an unexpected exit of the parent process.
                for (int attempt = 0; attempt < 15; attempt++)
                {
                    lock (gate)
                    {
                        ReleaseAll();
                        if (!AnyHeld) break;
                    }
                    Thread.Sleep(100);
                }
                lock (gate)
                {
                    if (AnyHeld) Fault("Windows rejected input release");
                }
            }
            return faulted ? 1 : 0;
        }

        private void Apply(string line)
        {
            if (line == "alive")
            {
                if (deadline != 0 && clock.ElapsedMilliseconds >= deadline)
                {
                    Fault("Keyboard input lease expired");
                    return;
                }
                deadline = clock.ElapsedMilliseconds + LeaseMs;
                return;
            }
            if (line == "reset")
            {
                deadline = 0;
                ReleaseAll();
                if (AnyHeld) Fault("Windows rejected input release");
                return;
            }

            if (deadline == 0 || clock.ElapsedMilliseconds >= deadline)
            {
                Fault("Keyboard input lease expired");
                return;
            }

            var parts = line.Split(' ');
            ushort key;
            uint button;
            int x, y;
            if (parts.Length == 2 && (parts[0] == "down" || parts[0] == "up") &&
                TryKey(parts[1], out key))
            {
                SetKey(key, parts[0] == "down");
            }
            else if (parts.Length == 2 && (parts[0] == "mouse-down" || parts[0] == "mouse-up") &&
                MouseButtons.TryGetValue(parts[1], out button))
            {
                SetMouse(button, parts[0] == "mouse-down");
            }
            else if (parts.Length == 3 && parts[0] == "move" &&
                TryDelta(parts[1], 512, out x) && TryDelta(parts[2], 512, out y))
            {
                if (!SendMouse(Move, x, y, 0)) SendFailure();
            }
            else if (parts.Length == 2 && parts[0] == "wheel" && TryDelta(parts[1], 8, out y))
            {
                if (!SendMouse(Wheel, 0, 0, unchecked((uint)(y * 120)))) SendFailure();
            }
            else Fault("Invalid Windows input command");
        }

        private bool AnyHeld { get { return held.Count != 0 || heldMouse.Count != 0; } }

        private static bool TryKey(string name, out ushort key)
        {
            if (Keys.TryGetValue(name, out key)) return true;
            if (name.Length == 1 && ((name[0] >= 'A' && name[0] <= 'Z') ||
                (name[0] >= '0' && name[0] <= '9')))
            {
                key = name[0];
                return true;
            }
            int index;
            if (name.Length >= 2 && name.Length <= 3 && name[0] == 'F' &&
                int.TryParse(name.Substring(1), NumberStyles.None, CultureInfo.InvariantCulture, out index) &&
                index >= 1 && index <= 12 && name == "F" + index.ToString(CultureInfo.InvariantCulture))
            {
                key = (ushort)(0x70 + index - 1);
                return true;
            }
            return false;
        }

        private static bool TryDelta(string value, int max, out int result)
        {
            return int.TryParse(value, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out result) &&
                result >= -max && result <= max;
        }

        private void SetKey(ushort key, bool down)
        {
            if (held.Contains(key) == down) return;
            if (!SendKey(key, down))
            {
                SendFailure();
                return;
            }
            if (down) held.Add(key);
            else held.Remove(key);
        }

        private void SetMouse(uint button, bool down)
        {
            if (heldMouse.Contains(button) == down) return;
            if (!SendMouse(down ? button : button << 1, 0, 0, 0))
            {
                SendFailure();
                return;
            }
            if (down) heldMouse.Add(button);
            else heldMouse.Remove(button);
        }

        private void SendFailure()
        {
            Fault("Windows SendInput failed (error " + Marshal.GetLastWin32Error() + ")");
        }

        private void CheckLease(object unused)
        {
            lock (gate)
            {
                if (faulted)
                {
                    ReleaseAll();
                    return;
                }
                if (deadline != 0 && clock.ElapsedMilliseconds >= deadline)
                {
                    ReleaseAll();
                    Fault("Keyboard input lease expired");
                }
            }
        }

        private void ReleaseAll()
        {
            heldMouse.RemoveWhere(button => SendMouse(button << 1, 0, 0, 0));
            // Release ordinary keys before modifiers so cleanup keeps chord ordering.
            held.RemoveWhere(key => (key < 0x10 || key > 0x12) && SendKey(key, false));
            held.RemoveWhere(key => key >= 0x10 && key <= 0x12 && SendKey(key, false));
        }

        private void Fault(string reason)
        {
            deadline = 0;
            if (faulted)
            {
                Console.Error.WriteLine(reason);
                return;
            }
            faulted = true;
            Console.WriteLine("error " + reason);
        }

        private static bool SendKey(ushort key, bool down)
        {
            var input = new Input { Type = Keyboard };
            input.Data.Keyboard.VirtualKey = key;
            input.Data.Keyboard.Flags = down ? 0 : KeyUp;
            if ((key >= 0x21 && key <= 0x28) || key == 0x2D || key == 0x2E)
                input.Data.Keyboard.Flags |= ExtendedKey;
            input.Data.Keyboard.ExtraInfo = EventTag;
            return SendInput(1, ref input, InputSize) == 1;
        }

        private static bool SendMouse(uint flags, int x, int y, uint data)
        {
            var input = new Input();
            input.Data.Mouse.X = x;
            input.Data.Mouse.Y = y;
            input.Data.Mouse.Flags = flags;
            input.Data.Mouse.MouseData = data;
            input.Data.Mouse.ExtraInfo = EventTag;
            return SendInput(1, ref input, InputSize) == 1;
        }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint count, ref Input input, int size);

        [StructLayout(LayoutKind.Sequential)]
        private struct Input
        {
            public uint Type;
            public InputData Data;
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct InputData
        {
            [FieldOffset(0)] public KeyboardInput Keyboard;
            // The full union gives INPUT the size and alignment expected by Win32.
            [FieldOffset(0)] public MouseInput Mouse;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct KeyboardInput
        {
            public ushort VirtualKey, ScanCode;
            public uint Flags, Time;
            public UIntPtr ExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MouseInput
        {
            public int X, Y;
            public uint MouseData, Flags, Time;
            public UIntPtr ExtraInfo;
        }
    }
}
