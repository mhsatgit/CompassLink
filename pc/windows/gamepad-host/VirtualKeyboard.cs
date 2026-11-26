using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using HIDMaestro;

namespace CompassLink;

internal sealed class VirtualKeyboard : IDisposable
{
    private const uint KeyboardDevice = 1;
    private const uint DeviceName = 0x20000007;
    private const string HardwareId = "VID_FFFF&PID_434C";
    private readonly HMController controller;
    private readonly HashSet<HMKey> keys = [];
    private HMKeyModifiers modifiers;

    private VirtualKeyboard(HMController controller) => this.controller = controller;

    internal static async Task<VirtualKeyboard> Attach(HMContext context, CancellationToken stop)
    {
        if (!context.IsDriverInstalled)
            throw new InvalidOperationException("The signed USB/IP driver is unavailable. Run the approved driver setup.");

        var before = EnumeratedDevices();
        var profile = new HMProfileBuilder()
            .Id("compasslink-keyboard")
            .Name("CompassLink Virtual Keyboard")
            .ManufacturerString("CompassLink")
            .ProductString("CompassLink Virtual Keyboard")
            .Type("keyboard")
            .Connection("usb")
            .Vid(0xFFFF).Pid(0x434C)
            .FromDescriptorBuilder(new HidDescriptorBuilder().KeyboardNkro())
            .Build();
        var keyboard = new VirtualKeyboard(context.CreateController(profile, "compasslink-keyboard"));
        try
        {
            var timeout = Stopwatch.StartNew();
            while (timeout.Elapsed < TimeSpan.FromSeconds(6))
            {
                if (EnumeratedDevices().Except(before).Any())
                {
                    keyboard.Clear();
                    return keyboard;
                }
                await Task.Delay(30, stop);
            }
            throw new TimeoutException("Windows Raw Input did not enumerate the CompassLink keyboard.");
        }
        catch
        {
            keyboard.Dispose();
            throw;
        }
    }

    internal void SetKey(byte key, bool down)
    {
        var modifier = key switch
        {
            0x10 => HMKeyModifiers.LeftShift,
            0x11 => HMKeyModifiers.LeftControl,
            0x12 => HMKeyModifiers.LeftAlt,
            _ => HMKeyModifiers.None
        };
        if (modifier != HMKeyModifiers.None)
        {
            modifiers = down ? modifiers | modifier : modifiers & ~modifier;
        }
        else
        {
            HMKey usage = MapKey(key);
            if (down) keys.Add(usage);
            else keys.Remove(usage);
        }
        controller.SubmitKeyboard(modifiers, keys.ToArray());
    }

    private static HMKey MapKey(byte key) => key switch
    {
        >= 0x41 and <= 0x5A => (HMKey)((int)HMKey.A + key - 0x41),
        >= 0x31 and <= 0x39 => (HMKey)((int)HMKey.D1 + key - 0x31),
        >= 0x70 and <= 0x7B => (HMKey)((int)HMKey.F1 + key - 0x70),
        0x30 => HMKey.D0,
        0x08 => HMKey.Backspace, 0x09 => HMKey.Tab, 0x0D => HMKey.Enter,
        0x1B => HMKey.Escape, 0x20 => HMKey.Space, 0x21 => HMKey.PageUp,
        0x22 => HMKey.PageDown, 0x23 => HMKey.End, 0x24 => HMKey.Home,
        0x25 => HMKey.Left, 0x26 => HMKey.Up, 0x27 => HMKey.Right, 0x28 => HMKey.Down,
        0x2D => HMKey.Insert, 0x2E => HMKey.Delete, 0xBA => HMKey.Semicolon,
        0xBB => HMKey.Equals, 0xBC => HMKey.Comma, 0xBD => HMKey.Minus,
        0xBE => HMKey.Period, 0xBF => HMKey.Slash, 0xC0 => HMKey.Grave,
        0xDB => HMKey.LeftBracket, 0xDC => HMKey.Backslash, 0xDD => HMKey.RightBracket,
        0xDE => HMKey.Apostrophe,
        _ => throw new InvalidDataException("Unsupported virtual keyboard key.")
    };

    internal void Clear()
    {
        keys.Clear();
        modifiers = HMKeyModifiers.None;
        controller.SubmitKeyboard(modifiers, ReadOnlySpan<HMKey>.Empty);
    }

    public void Dispose()
    {
        try { Clear(); }
        finally { controller.Dispose(); }
    }

    internal static async Task Verify(HMContext context, CancellationToken stop)
    {
        var before = EnumeratedDevices();
        using (var keyboard = await Attach(context, stop))
        {
            Console.WriteLine("Windows Raw Input enumerated the CompassLink keyboard.");
            keyboard.controller.SubmitKeyboard(HMKeyModifiers.None, [HMKey.F24]);
            await WaitFor(() => (GetAsyncKeyState(0x87) & 0x8000) != 0, "virtual F24 down", stop);
            keyboard.Clear();
            await WaitFor(() => (GetAsyncKeyState(0x87) & 0x8000) == 0, "virtual F24 up", stop);
        }
        await WaitFor(() => !EnumeratedDevices().Except(before).Any(), "keyboard removal", stop);
    }

    private static async Task WaitFor(Func<bool> condition, string label, CancellationToken stop)
    {
        var timeout = Stopwatch.StartNew();
        while (!condition())
        {
            if (timeout.Elapsed > TimeSpan.FromSeconds(5))
                throw new TimeoutException("Windows did not report " + label);
            await Task.Delay(20, stop);
        }
        Console.WriteLine("Windows verified " + label + ".");
    }

    private static string[] EnumeratedDevices()
    {
        uint count = 0;
        uint size = (uint)Marshal.SizeOf<RawDevice>();
        if (GetRawInputDeviceList(null, ref count, size) == uint.MaxValue || count == 0)
            return [];
        var list = new RawDevice[count];
        uint actual = GetRawInputDeviceList(list, ref count, size);
        if (actual == uint.MaxValue) return [];
        var names = new List<string>();
        foreach (RawDevice device in list.Take((int)actual).Where(device => device.Kind == KeyboardDevice))
        {
            uint characters = 0;
            if (GetRawInputDeviceInfo(device.Handle, DeviceName, null, ref characters) == uint.MaxValue || characters == 0)
                continue;
            var name = new StringBuilder((int)characters);
            if (GetRawInputDeviceInfo(device.Handle, DeviceName, name, ref characters) != uint.MaxValue &&
                name.ToString().Contains(HardwareId, StringComparison.OrdinalIgnoreCase))
                names.Add(name.ToString());
        }
        return names.ToArray();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RawDevice
    {
        public IntPtr Handle;
        public uint Kind;
    }

    [DllImport("user32.dll")]
    private static extern uint GetRawInputDeviceList([Out] RawDevice[]? devices, ref uint count, uint size);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern uint GetRawInputDeviceInfo(IntPtr device, uint command, StringBuilder? data, ref uint characters);

    [DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int key);
}
