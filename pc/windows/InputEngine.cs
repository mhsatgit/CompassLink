using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

namespace CompassLink
{
    public sealed class InputEngine
    {
        private const uint Keyboard = 1;
        private const uint KeyUp = 2;
        private const int LeaseMs = 3000;
        private static readonly UIntPtr EventTag = new UIntPtr(0x434C4E4B);
        private static readonly int InputSize = Marshal.SizeOf(typeof(Input));
        private static readonly Dictionary<string, ushort> Keys = new Dictionary<string, ushort>
        {
            { "W", 0x57 }, { "A", 0x41 }, { "S", 0x53 }, { "D", 0x44 },
            { "SPACE", 0x20 }, { "ESCAPE", 0x1B }
        };

        private readonly object gate = new object();
        private readonly HashSet<ushort> held = new HashSet<ushort>();
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
                        if (held.Count == 0) break;
                    }
                    Thread.Sleep(100);
                }
                lock (gate)
                {
                    if (held.Count != 0) Fault("Windows rejected key release");
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
                if (held.Count != 0) Fault("Windows rejected key release");
                return;
            }

            var parts = line.Split(' ');
            ushort key;
            if (parts.Length != 2 || !Keys.TryGetValue(parts[1], out key) ||
                (parts[0] != "down" && parts[0] != "up"))
            {
                Fault("Invalid keyboard command");
                return;
            }
            if (deadline == 0 || clock.ElapsedMilliseconds >= deadline)
            {
                Fault("Keyboard input lease expired");
                return;
            }

            bool down = parts[0] == "down";
            if (held.Contains(key) == down) return;
            if (!Send(key, down))
            {
                Fault("Windows SendInput failed (error " + Marshal.GetLastWin32Error() + ")");
                return;
            }
            if (down) held.Add(key);
            else held.Remove(key);
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
            held.RemoveWhere(key => Send(key, false));
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

        private static bool Send(ushort key, bool down)
        {
            var input = new Input { Type = Keyboard };
            input.Data.Keyboard.VirtualKey = key;
            input.Data.Keyboard.Flags = down ? 0 : KeyUp;
            input.Data.Keyboard.ExtraInfo = EventTag;
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
