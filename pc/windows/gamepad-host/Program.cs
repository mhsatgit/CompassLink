using System.Buffers.Binary;
using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using HIDMaestro;

namespace CompassLink;

internal static class Program
{
    private const string Profile = "xbox-360-wired";
    private const byte Status = 0, Connect = 1, Report = 2, Heartbeat = 3, Disconnect = 4, Neutral = 5;
    private const int PayloadSize = 26;
    private static readonly TimeSpan Lease = TimeSpan.FromSeconds(3);

    private static async Task<int> Main(string[] args)
    {
        if (!OperatingSystem.IsWindows() || RuntimeInformation.ProcessArchitecture != Architecture.X64)
        {
            Console.Error.WriteLine("CompassLink Gamepad Host requires x64 Windows.");
            return 1;
        }
        using var identity = WindowsIdentity.GetCurrent();
        if (!new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator) || identity.User is null)
        {
            Console.Error.WriteLine("Start the gamepad helper with administrator privileges.");
            return 1;
        }

        Environment.SetEnvironmentVariable("HIDMAESTRO_KEEP_TRANSPORT", "1");
        using var context = new HMContext(HMBackend.Usbip);
        if (args.Length == 2 && args[0] == "--verify-keyboard")
        {
            using var report = new StreamWriter(args[1]) { AutoFlush = true };
            Console.SetOut(report);
            try { await VirtualKeyboard.Verify(context, CancellationToken.None); }
            catch (Exception error) { Console.WriteLine(error); return 1; }
            return 0;
        }
        context.LoadDefaultProfiles();
        var profile = context.GetProfile(Profile)
            ?? throw new InvalidOperationException("HIDMaestro's Xbox 360 profile is unavailable.");
        using var stopped = new CancellationTokenSource();
        Console.CancelKeyPress += (_, eventArgs) => { eventArgs.Cancel = true; stopped.Cancel(); };
        var gamepad = RunGamepad(identity.User.Value, context, profile, stopped.Token);
        var keyboard = KeyboardHost.Run(identity.User.Value, context, stopped.Token);
        await Task.WhenAny(gamepad, keyboard);
        stopped.Cancel();
        await Task.WhenAll(gamepad, keyboard);
        return 0;
    }

    private static async Task RunGamepad(string owner, HMContext context, HMProfile profile, CancellationToken stop)
    {
        while (!stop.IsCancellationRequested)
        {
            using var pipe = LocalPipe.Open(owner);
            using var idle = CancellationTokenSource.CreateLinkedTokenSource(stop);
            idle.CancelAfter(TimeSpan.FromSeconds(90));
            try { await pipe.WaitForConnectionAsync(idle.Token); }
            catch (OperationCanceledException) { break; }
            try { await Serve(pipe, context, profile, stop); }
            catch (EndOfStreamException) { }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { break; }
            catch (Exception error)
            {
                Console.Error.WriteLine(error.Message);
                if (!pipe.IsConnected) continue;
                try { await Write(pipe, new { type = "error", message = error.Message }, stop); }
                catch (IOException) { }
                catch (OperationCanceledException) { }
            }
        }
    }

    private static async Task Serve(NamedPipeServerStream pipe, HMContext context,
        HMProfile profile, CancellationToken stop)
    {
        var operation = new byte[1];
        await Read(pipe, operation, stop);
        bool installed = context.IsDriverInstalled;
        if (operation[0] == Status)
        {
            await Write(pipe, new { type = "status", installed }, stop);
            return;
        }
        if (operation[0] != Connect) throw new InvalidDataException("Expected a status or connect request.");
        if (!installed) throw new InvalidOperationException("The USB/IP driver is unavailable. Run the approved driver setup.");
        uint occupied = XInput.AttachedSlots();
        if (occupied == 0xF) throw new InvalidOperationException("All four Windows XInput slots are in use.");
        using var controller = context.CreateController(profile, "compasslink-xbox-gamepad");
        var neutral = new HMGamepadState { Hat = HMHat.None, Axes = HMGamepadStateHelpers.StandardAxes(profile) };
        using var active = CancellationTokenSource.CreateLinkedTokenSource(stop);
        using var writes = new SemaphoreSlim(1);
        Task? watcher = null;
        try
        {
            controller.SubmitState(in neutral);
            uint slot = await LocateSlot(occupied, active.Token);
            await Write(pipe, new { type = "ready", slot = slot + 1 }, active.Token);
            watcher = Observe(pipe, slot, active, writes);

            var payload = new byte[PayloadSize];
            while (true)
            {
                await Read(pipe, operation, active.Token);
                if (operation[0] == Disconnect) return;
                if (operation[0] == Heartbeat) continue;
                if (operation[0] == Neutral)
                {
                    controller.SubmitState(in neutral);
                    var wait = Stopwatch.StartNew();
                    while (XInput.Read(slot, out var pad) != XInput.Success || !pad.IsNeutral)
                    {
                        if (wait.Elapsed > TimeSpan.FromSeconds(1))
                            throw new IOException("Windows did not confirm a neutral Xbox controller.");
                        await Task.Delay(10, active.Token);
                    }
                    await Write(pipe, new { type = "neutral" }, active.Token, writes);
                    continue;
                }
                if (operation[0] != Report) throw new InvalidDataException("Unknown gamepad host command.");
                await Read(pipe, payload, active.Token);
                var state = Decode(profile, payload);
                controller.SubmitState(in state);
            }
        }
        finally
        {
            active.Cancel();
            controller.SubmitState(in neutral);
            if (watcher is not null)
            {
                try { await watcher; }
                catch (OperationCanceledException) when (active.IsCancellationRequested) { }
            }
        }
    }

    private static async Task<uint> LocateSlot(uint occupied, CancellationToken token)
    {
        var timeout = Stopwatch.StartNew();
        while (timeout.Elapsed < TimeSpan.FromSeconds(6))
        {
            uint newSlots = XInput.AttachedSlots() & ~occupied;
            if (newSlots != 0 && (newSlots & (newSlots - 1)) == 0)
            {
                uint slot = (uint)System.Numerics.BitOperations.TrailingZeroCount(newSlots);
                if (XInput.Read(slot, out var observed) == XInput.Success && observed.IsNeutral) return slot;
            }
            else if (newSlots != 0)
                throw new InvalidOperationException("Several new XInput devices appeared during creation. Retry with a stable set of controllers.");
            await Task.Delay(20, token);
        }
        throw new TimeoutException("Windows did not enumerate a neutral virtual Xbox controller.");
    }

    private static async Task Observe(Stream pipe, uint slot, CancellationTokenSource active, SemaphoreSlim writes)
    {
        XInput.Pad? previous = null;
        int failures = 0;
        try
        {
            using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(65));
            while (await timer.WaitForNextTickAsync(active.Token))
            {
                if (XInput.Read(slot, out var pad) != XInput.Success)
                {
                    if (++failures == 3)
                        throw new IOException("The virtual controller disappeared from Windows XInput.");
                    continue;
                }
                failures = 0;
                if (previous is XInput.Pad last && last.Equals(pad)) continue;
                previous = pad;
                await Write(pipe, new
                {
                    type = "observed", slot = slot + 1, buttons = pad.Buttons,
                    leftX = pad.LeftX, leftY = pad.LeftY, rightX = pad.RightX, rightY = pad.RightY,
                    leftTrigger = pad.LeftTrigger, rightTrigger = pad.RightTrigger
                }, active.Token, writes);
            }
        }
        catch
        {
            active.Cancel();
            throw;
        }
    }

    private static HMGamepadState Decode(HMProfile profile, byte[] bytes)
    {
        ushort buttons = BinaryPrimitives.ReadUInt16LittleEndian(bytes);
        if ((buttons & ~0xF3FF) != 0) throw new InvalidDataException("Unknown Xbox button mask.");
        HMButton flags = HMButton.None;
        foreach (var (bit, flag) in ButtonFlags)
            if ((buttons & bit) != 0) flags |= flag;
        var hat = (buttons & 0xF) switch
        {
            0 => HMHat.None, 1 => HMHat.North, 2 => HMHat.South, 4 => HMHat.West, 8 => HMHat.East,
            5 => HMHat.NorthWest, 9 => HMHat.NorthEast, 6 => HMHat.SouthWest, 10 => HMHat.SouthEast,
            _ => throw new InvalidDataException("Opposing D-pad directions are invalid.")
        };
        float Axis(int offset, float min)
        {
            float value = BinaryPrimitives.ReadSingleLittleEndian(bytes.AsSpan(offset));
            if (!float.IsFinite(value) || value < min || value > 1)
                throw new InvalidDataException("Gamepad axis is outside the normalized range.");
            return value;
        }
        return new HMGamepadState
        {
            Buttons = flags, Hat = hat,
            // HIDMaestro's stick coordinates are 0–1 with Y down. XInput reads positive Y as up.
            Axes = HMGamepadStateHelpers.StandardAxes(profile, (1 + Axis(2, -1)) / 2, (1 - Axis(6, -1)) / 2,
                (1 + Axis(10, -1)) / 2, (1 - Axis(14, -1)) / 2, Axis(18, 0), Axis(22, 0))
        };
    }

    private static async Task Read(Stream pipe, byte[] bytes, CancellationToken stop)
    {
        using var lease = CancellationTokenSource.CreateLinkedTokenSource(stop);
        lease.CancelAfter(Lease);
        try { await pipe.ReadExactlyAsync(bytes, lease.Token); }
        catch (OperationCanceledException) when (!stop.IsCancellationRequested)
        {
            throw new TimeoutException("The gamepad host session lease expired.");
        }
    }

    private static async Task Write(Stream pipe, object message, CancellationToken stop, SemaphoreSlim? writes = null)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(stop);
        deadline.CancelAfter(Lease);
        if (writes is not null) await writes.WaitAsync(deadline.Token);
        try { await pipe.WriteAsync(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(message) + "\n"), deadline.Token); }
        finally { writes?.Release(); }
    }

    private static readonly (ushort, HMButton)[] ButtonFlags =
    [
        (0x0010, HMButton.Start), (0x0020, HMButton.Back), (0x0040, HMButton.LeftStick),
        (0x0080, HMButton.RightStick), (0x0100, HMButton.LeftBumper), (0x0200, HMButton.RightBumper),
        (0x1000, HMButton.A), (0x2000, HMButton.B), (0x4000, HMButton.X), (0x8000, HMButton.Y)
    ];
}
