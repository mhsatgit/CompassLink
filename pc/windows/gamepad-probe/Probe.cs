using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Threading;
using static CompassLink.XInput;
using HIDMaestro;

namespace CompassLink;

public static class GamepadProbe
{
    private const string ProfileId = "xbox-360-wired";
    private const string ProbeIdentity = "compasslink-gamepad-probe";
    private const ushort AButton = 0x1000;

    public static void Run(TextWriter log, bool verify, bool installDriver)
    {
        if (!OperatingSystem.IsWindows() || RuntimeInformation.ProcessArchitecture != Architecture.X64)
            throw new PlatformNotSupportedException("The CompassLink gamepad probe requires x64 Windows.");

        using var identity = WindowsIdentity.GetCurrent();
        var elevated = new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
        log.WriteLine($"Platform: {Environment.OSVersion.Version}; runtime: {Environment.Version}; elevated: {elevated}");
        log.WriteLine($"HIDMaestro assembly: {typeof(HMContext).Assembly.GetName().Version}; backend: USB/IP");
        log.WriteLine($"XInput slots present before test: 0x{AttachedSlots():X1}");

        using var context = new HMContext(HMBackend.Usbip);
        context.LoadDefaultProfiles();
        var profile = context.GetProfile(ProfileId) ?? throw new InvalidOperationException("The pinned SDK does not provide xbox-360-wired.");
        if (!profile.IsDeployable || !profile.SupportsBackend(HMBackend.Usbip))
            throw new InvalidOperationException("The Xbox 360 preset does not support this backend.");

        log.WriteLine($"Xbox profile: {profile.Id}; VID/PID {profile.VendorId:X4}/{profile.ProductId:X4}");
        log.WriteLine($"USB/IP host controller ready: {context.IsDriverInstalled}");
        if (!verify) return;

        if (!elevated)
            throw new UnauthorizedAccessException("Run -Verify from an administrator PowerShell session.");
        if (installDriver)
        {
            context.InstallDriver();
            log.WriteLine($"Driver setup: {context.LastDriverInstall}");
        }
        if (!context.IsDriverInstalled)
            throw new InvalidOperationException("The USB/IP host controller is missing or unavailable. Run explicit driver setup with approval.");

        uint before = AttachedSlots();
        if (before == 0xF)
            throw new InvalidOperationException("XInput has four occupied slots. Free a slot for the test controller.");

        var neutral = new HMGamepadState { Axes = HMGamepadStateHelpers.StandardAxes(profile), Hat = HMHat.None };
        uint slot;
        using (var controller = context.CreateController(profile, ProbeIdentity))
        {
            try
            {
                var button = neutral;
                button.Buttons = HMButton.A;
                controller.SubmitState(in button);
                slot = FindNewSlot(before);
                log.WriteLine($"Windows enumerated the probe controller at XInput slot {slot + 1}.");
                Check(log, slot, "A down", pad => pad.Buttons == AButton && pad.IsCentered);

                controller.SubmitState(in neutral);
                Check(log, slot, "A up", pad => pad.IsNeutral);

                var active = new HMGamepadState
                {
                    Buttons = HMButton.A | HMButton.B | HMButton.X | HMButton.Y | HMButton.LeftBumper |
                        HMButton.RightBumper | HMButton.Start | HMButton.Back | HMButton.LeftStick | HMButton.RightStick,
                    Hat = HMHat.NorthEast,
                    Axes = HMGamepadStateHelpers.StandardAxes(profile, 1f, 0f, 0f, 1f, .25f, .75f)
                };
                controller.SubmitState(in active);
                Check(log, slot, "digital and analog", pad =>
                    pad.Buttons == 0xF3F9 &&
                    pad.LeftX >= 32766 && pad.LeftY >= 32766 &&
                    pad.RightX <= -32767 && pad.RightY <= -32767 &&
                    Math.Abs(pad.LeftTrigger - 64) <= 1 && Math.Abs(pad.RightTrigger - 191) <= 1);

                var full = neutral;
                full.Axes = HMGamepadStateHelpers.StandardAxes(profile, leftTrigger: 1f, rightTrigger: 1f);
                controller.SubmitState(in full);
                Check(log, slot, "both triggers full", pad => pad.Buttons == 0 && pad.IsCentered &&
                    pad.LeftTrigger == 255 && pad.RightTrigger == 255);
            }
            finally
            {
                controller.SubmitState(in neutral);
            }

            Check(log, slot, "neutral before disposal", pad => pad.IsNeutral);
        }

        WaitFor(() => Read(slot, out _) == Disconnected, $"XInput slot {slot + 1} did not disconnect");
        log.WriteLine($"Windows removed XInput slot {slot + 1} after disposal.");
    }

    private static uint FindNewSlot(uint before)
    {
        uint candidate = uint.MaxValue;
        WaitFor(() =>
        {
            for (uint slot = 0; slot < 4; slot++)
            {
                if ((before & (1u << (int)slot)) == 0 && Read(slot, out var pad) == Success &&
                    pad.Buttons == AButton && pad.IsCentered)
                {
                    candidate = slot;
                    return true;
                }
            }
            return false;
        }, "Windows did not report a new XInput slot with the probe's A-button marker");
        return candidate;
    }

    private static void Check(TextWriter log, uint slot, string step, Func<Pad, bool> accept)
    {
        Pad last = default;
        uint status = Disconnected;
        try
        {
            WaitFor(() => (status = Read(slot, out last)) == Success && accept(last), step);
        }
        catch (TimeoutException error)
        {
            throw new TimeoutException($"{error.Message}. Last XInput status {status}, {last}.", error);
        }
        log.WriteLine($"{step}: {last}");
    }

    private static void WaitFor(Func<bool> condition, string description)
    {
        var clock = Stopwatch.StartNew();
        while (!condition())
        {
            if (clock.Elapsed >= TimeSpan.FromSeconds(8)) throw new TimeoutException(description);
            Thread.Sleep(15);
        }
    }

}
